package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.util.ContentTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * 单体后端应用 HTTP 反向代理：
 * - 透传全部 HTTP 方法（GET/POST/PUT/PATCH/DELETE/OPTIONS/HEAD）、查询串、请求体与响应头
 * - 剥离客户端伪造的 X-Zhiye-* 请求头，并注入受信任的华星 SSO 登录身份头
 * - 自动按需唤醒休眠态（Scale-to-Zero）的单体服务并刷新 last_access_at
 * - 前后端一体（local 模式含 static/ 静态产物时）支持静态资源直出与后端 /api/* 代理无缝同域协同
 */
@Component
public class ServiceProxyHandler {
    private static final Logger log = LoggerFactory.getLogger(ServiceProxyHandler.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length"
    );

    /** 严禁透传到业务应用容器的平台敏感 Cookie 名称（前缀不区分大小写）。 */
    private static final Set<String> PLATFORM_COOKIES = Set.of(
            "zhiye_ws", "zhiye_sso", "zhiye_admin", "zhiye_session", "jsessionid"
    );

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final MonolithServiceManager manager;
    private final ServiceRuntimeProvider provider;

    public ServiceProxyHandler(MonolithServiceManager manager, ServiceRuntimeProvider provider) {
        this.manager = manager;
        this.provider = provider;
    }

    /** 反向代理请求到单体服务，返回上游响应的 Content-Type（用于统计页面 PV）。 */
    public String proxy(AppRow app, String targetPath, HttpServletRequest req, HttpServletResponse res,
                        SsoUser user, boolean isAdmin) throws IOException {
        return proxy(app, targetPath, req, res, user, isAdmin, false);
    }

    /** 反向代理请求到单体服务；previewMode=true 时剥离上游 X-Frame-Options 以确保工作台预览 iframe 正常渲染。 */
    public String proxy(AppRow app, String targetPath, HttpServletRequest req, HttpServletResponse res,
                        SsoUser user, boolean isAdmin, boolean previewMode) throws IOException {
        String cleanPath = (targetPath == null || targetPath.isEmpty()) ? "/" : (targetPath.startsWith("/") ? targetPath : "/" + targetPath);

        String previewToken = previewMode && req.getAttribute("zhiye.preview.token") instanceof String s ? s : null;

        // 前后端一体（本地模式）：非 /api 请求优先匹配对应槽位（预览 vs 线上）已构建的静态资源
        if (provider instanceof LocalServiceRuntimeProvider local
                && ("GET".equalsIgnoreCase(req.getMethod()) || "HEAD".equalsIgnoreCase(req.getMethod()))
                && !cleanPath.startsWith("/api/") && !cleanPath.equals("/api")) {
            Path staticDir = local.staticDir(app.id(), previewMode);
            if (Files.isDirectory(staticDir) && tryServeStatic(staticDir, cleanPath, res, "HEAD".equalsIgnoreCase(req.getMethod()), previewToken)) {
                manager.recordAccess(app.id());
                return res.getContentType();
            }
        }

        String endpoint;
        try {
            endpoint = previewMode ? manager.ensurePreviewAwake(app.id()) : manager.ensureAwake(app.id());
        } catch (Exception e) {
            log.warn("唤醒单体应用失败 app={} slug={} preview={}: {}", app.id(), app.slug(), previewMode, e.getMessage());
            writeError(res, 503, "应用正在启动或已停止", e.getMessage());
            return null;
        }
        manager.recordAccess(app.id());

        String query = req.getQueryString();
        String base = endpoint.replaceAll("/+$", "");
        String encodedPath = encodeUriPath(cleanPath);
        URI targetUri = URI.create(base + encodedPath + (query != null && !query.isEmpty() ? "?" + query : ""));

        String method = req.getMethod().toUpperCase(Locale.ROOT);
        byte[] bodyBytes = ("GET".equals(method) || "HEAD".equals(method)) ? new byte[0] : req.getInputStream().readAllBytes();
        if (bodyBytes.length == 0 && !("GET".equals(method) || "HEAD".equals(method))) {
            String reqCt = req.getContentType();
            if (reqCt != null && reqCt.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
                Map<String, String[]> params = req.getParameterMap();
                if (params != null && !params.isEmpty()) {
                    StringBuilder form = new StringBuilder();
                    for (var e : params.entrySet()) {
                        for (String val : e.getValue()) {
                            if (!form.isEmpty()) form.append('&');
                            form.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                                    .append('=')
                                    .append(URLEncoder.encode(val == null ? "" : val, StandardCharsets.UTF_8));
                        }
                    }
                    bodyBytes = form.toString().getBytes(StandardCharsets.UTF_8);
                }
            }
        }
        HttpRequest.BodyPublisher publisher = bodyBytes.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(bodyBytes);

        HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                .timeout(Duration.ofSeconds(60))
                .method(method, publisher);

        Enumeration<String> headerNames = req.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String h = headerNames.nextElement();
            String lower = h.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || lower.startsWith("x-zhiye-")) continue;
            // 安全边界（FS-02）：工作台同域预览绝不向用户容器透传平台 Cookie / Authorization
            if (previewMode && ("cookie".equals(lower) || "authorization".equals(lower))) continue;
            if (!previewMode && "cookie".equals(lower)) {
                String filtered = stripPlatformCookies(req.getHeader(h));
                if (!filtered.isEmpty()) builder.header("Cookie", filtered);
                continue;
            }
            Enumeration<String> vals = req.getHeaders(h);
            while (vals.hasMoreElements()) {
                builder.header(h, vals.nextElement());
            }
        }

        // 注入可信的平台与 SSO 员工身份头
        builder.header("X-Zhiye-App-Id", app.id());
        builder.header("X-Zhiye-App-Slug", app.slug());
        if (user != null) {
            builder.header("X-Zhiye-User-Id", user.id());
            if (user.loginName() != null) builder.header("X-Zhiye-User-Login", user.loginName());
            String displayName = user.realName() != null && !user.realName().isBlank() ? user.realName() : user.loginName();
            if (displayName != null) builder.header("X-Zhiye-User-Name", URLEncoder.encode(displayName, StandardCharsets.UTF_8));
            if (user.email() != null) builder.header("X-Zhiye-User-Email", user.email());
            builder.header("X-Zhiye-User-Admin", String.valueOf(isAdmin));
        }
        String host = req.getHeader("Host");
        if (host != null && !host.isBlank()) builder.header("X-Forwarded-Host", host);
        builder.header("X-Forwarded-Proto", req.getScheme());
        if (req.getRemoteAddr() != null) builder.header("X-Forwarded-For", req.getRemoteAddr());

        HttpResponse<InputStream> up;
        try {
            up = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeError(res, 504, "请求被中断", e.getMessage());
            return null;
        } catch (IOException e) {
            writeError(res, 502, "应用服务暂时不可达", "连接应用后端失败：" + e.getMessage());
            return null;
        }

        // 前后端一体 SPA 回退：后端对非 /api 路径返回 404 时尝试回退到前端 static/index.html
        if (up.statusCode() == 404 && provider instanceof LocalServiceRuntimeProvider local
                && "GET".equals(method) && !cleanPath.startsWith("/api/") && !ContentTypes.hasExtension(cleanPath)) {
            Path index = local.staticDir(app.id(), previewMode).resolve("index.html");
            if (Files.isRegularFile(index)) {
                try (InputStream ignored = up.body()) {}
                res.setStatus(200);
                res.setContentType("text/html; charset=utf-8");
                res.setHeader("Cache-Control", "no-cache");
                writeHtmlWithPreviewShim(Files.readAllBytes(index), previewToken, res);
                return "text/html; charset=utf-8";
            }
        }

        String upContentType = up.headers().firstValue("content-type").orElse("");
        boolean injectHtmlShim = previewToken != null && !"HEAD".equals(method)
                && upContentType.toLowerCase(Locale.ROOT).contains("text/html")
                && up.headers().firstValue("content-encoding").isEmpty();

        res.setStatus(up.statusCode());
        for (var entry : up.headers().map().entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower)) continue;
            if (injectHtmlShim && "content-length".equals(lower)) continue;
            // 工作台预览模式：剥离 X-Frame-Options，且禁止用户应用向平台主域写入 Set-Cookie
            if (previewMode && ("x-frame-options".equals(lower) || "set-cookie".equals(lower))) continue;
            for (String v : entry.getValue()) {
                if (previewMode && "content-security-policy".equals(lower)) {
                    String relaxed = stripFrameAncestors(v);
                    if (!relaxed.isBlank()) res.addHeader(entry.getKey(), relaxed);
                    continue;
                }
                res.addHeader(entry.getKey(), v);
            }
        }
        try (InputStream in = up.body()) {
            if (!"HEAD".equals(method)) {
                if (injectHtmlShim) {
                    writeHtmlWithPreviewShim(in.readAllBytes(), previewToken, res);
                } else {
                    in.transferTo(res.getOutputStream());
                }
            }
        }
        return up.headers().firstValue("content-type").orElse(null);
    }

    private boolean tryServeStatic(Path staticDir, String cleanPath, HttpServletResponse res, boolean head, String previewToken) throws IOException {
        String rel = cleanPath.replaceFirst("^/+", "");
        if (rel.contains("..")) return false;
        Path target = rel.isEmpty() || rel.endsWith("/") ? staticDir.resolve(rel + "index.html") : staticDir.resolve(rel);
        if (!target.normalize().startsWith(staticDir.normalize()) || !Files.isRegularFile(target)) return false;
        String ct = ContentTypes.of(target.getFileName().toString());
        res.setStatus(200);
        res.setContentType(ct);
        res.setHeader("X-Content-Type-Options", "nosniff");
        if (!head) {
            if (previewToken != null && ct.toLowerCase(Locale.ROOT).contains("text/html")) {
                writeHtmlWithPreviewShim(Files.readAllBytes(target), previewToken, res);
            } else {
                Files.copy(target, res.getOutputStream());
            }
        }
        return true;
    }

    /** 预览模式 HTML 注入轻量 fetch/XHR 令牌头补丁，彻底避免跨端口/子域 iframe 在 Chrome 下因 SameSite Cookie 被拦导致白屏或 API 404。 */
    private static void writeHtmlWithPreviewShim(byte[] raw, String previewToken, HttpServletResponse res) throws IOException {
        if (previewToken == null || previewToken.isBlank()) {
            res.getOutputStream().write(raw);
            return;
        }
        String html = new String(raw, StandardCharsets.UTF_8);
        String safeTok = previewToken.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "");
        String shim = "<script>(function(t){try{var f=window.fetch;if(f){window.fetch=function(u,o){o=Object.assign({},o);var h=new Headers(o.headers||{});if(!h.has('X-Zhiye-Preview'))h.set('X-Zhiye-Preview',t);o.headers=h;return f.call(this,u,o);};}var X=XMLHttpRequest.prototype.open,S=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.open=function(){this.__zp=1;return X.apply(this,arguments);};XMLHttpRequest.prototype.send=function(){if(this.__zp)try{this.setRequestHeader('X-Zhiye-Preview',t);}catch(e){}return S.apply(this,arguments);};}catch(e){}})(\"" + safeTok + "\");</script>";
        int idx = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        String out = idx >= 0 ? html.substring(0, idx) + shim + html.substring(idx) : shim + html;
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
    }

    private static void writeError(HttpServletResponse res, int status, String title, String detail) throws IOException {
        res.setStatus(status);
        res.setContentType("text/html; charset=utf-8");
        String html = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>" + esc(title)
                + "</title><style>body{font-family:system-ui,-apple-system,sans-serif;margin:0;min-height:100vh;display:grid;place-items:center;background:#f8fafc;color:#0f172a}"
                + ".card{max-width:480px;background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:32px;box-shadow:0 12px 32px rgba(15,23,42,.06)}"
                + "h1{margin:0 0 10px;font-size:20px}p{margin:0;color:#475569;font-size:14px;line-height:1.7;white-space:pre-wrap}</style></head>"
                + "<body><div class=\"card\"><h1>" + esc(title) + "</h1><p>" + esc(detail == null ? "" : detail) + "</p></div></body></html>";
        res.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripFrameAncestors(String csp) {
        if (csp == null || csp.isBlank()) return "";
        String[] parts = csp.split(";");
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty() && !t.toLowerCase(Locale.ROOT).startsWith("frame-ancestors")) {
                kept.add(t);
            }
        }
        return String.join("; ", kept);
    }

    private static String encodeUriPath(String decodedPath) {
        String[] segs = decodedPath.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            if (!segs[i].isEmpty()) {
                sb.append(URLEncoder.encode(segs[i], StandardCharsets.UTF_8).replace("+", "%20"));
            }
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    private static String stripPlatformCookies(String rawCookie) {
        if (rawCookie == null || rawCookie.isBlank()) return "";
        String[] pairs = rawCookie.split(";");
        List<String> kept = new ArrayList<>();
        for (String p : pairs) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            String name = (eq > 0 ? trimmed.substring(0, eq) : trimmed).trim().toLowerCase(Locale.ROOT);
            if (PLATFORM_COOKIES.contains(name) || name.startsWith("zhiye_")) continue;
            kept.add(trimmed);
        }
        return String.join("; ", kept);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
