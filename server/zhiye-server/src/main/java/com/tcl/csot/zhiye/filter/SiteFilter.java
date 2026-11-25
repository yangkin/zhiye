package com.tcl.csot.zhiye.filter;

import com.tcl.csot.zhiye.service.site.PageViewRecorder;

import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.controller.CloudDataController;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.util.ContentTypes;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.repository.VersionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 预览与站点网关（profile=site）：
 * - GET /p/{buildId}/** → 对象存储中的预览版（带 data-loc 和编辑桥）
 * - GET /t/{templateId}/** → 织友作品的页面快照（静态，不注入令牌，看不到原应用的数据）
 * - Host 为 {slug}.localhost:8083 → 该应用线上版本的发布版
 */
@Component
@Profile("site")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SiteFilter extends OncePerRequestFilter {
    private static final Pattern PREVIEW = Pattern.compile("^/p/([A-Za-z0-9_\\-]+)(/.*)?$");
    private static final Pattern TEMPLATE = Pattern.compile("^/t/(tpl_[a-z0-9]+)(/.*)?$");

    private final ObjectStore store;
    private final VersionRepository versions;
    private final AppRepository apps;
    private final ZhiyeProperties props;
    private final Pattern hostPattern;
    /** 预览域名（previewBase 的 host）：命中发布站点泛域名时也不能当成应用 slug，要走 /p 预览 */
    private final String previewHost;
    private final PageViewRecorder pageViews;
    private final CloudService cloud;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final com.tcl.csot.zhiye.service.source.DataSourceService sources;
    private final AccessService access;
    private final com.tcl.csot.zhiye.service.cloud.AppAiService ai;
    private com.tcl.csot.zhiye.service.runtime.ServiceProxyHandler serviceProxy;
    private com.tcl.csot.zhiye.service.runtime.ServiceCrypto serviceCrypto;
    private static final Pattern SOURCE = Pattern.compile("^/__zhiye/source/([a-z][a-z0-9_]{1,47})$");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setServiceProxy(com.tcl.csot.zhiye.service.runtime.ServiceProxyHandler serviceProxy) {
        this.serviceProxy = serviceProxy;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setServiceCrypto(com.tcl.csot.zhiye.service.runtime.ServiceCrypto serviceCrypto) {
        this.serviceCrypto = serviceCrypto;
    }

    public SiteFilter(ObjectStore store, VersionRepository versions, AppRepository apps, ZhiyeProperties props, PageViewRecorder pageViews,
                      CloudService cloud, org.springframework.jdbc.core.simple.JdbcClient jdbc, com.tcl.csot.zhiye.service.source.DataSourceService sources,
                      AccessService access, com.tcl.csot.zhiye.service.cloud.AppAiService ai) {
        this.access = access;
        this.ai = ai;
        this.sources = sources;
        this.cloud = cloud;
        this.jdbc = jdbc;
        this.pageViews = pageViews;
        this.store = store;
        this.versions = versions;
        this.apps = apps;
        this.props = props;
        String suffix = props.site().siteHostSuffix().replaceFirst(":\\d+$", "");
        this.hostPattern = Pattern.compile("^([a-z0-9][a-z0-9-]{1,62})\\." + Pattern.quote(suffix) + "(:\\d+)?$", Pattern.CASE_INSENSITIVE);
        String pb = props.site().previewBase();
        String ph = null;
        if (pb != null && !pb.isBlank()) {
            try { ph = java.net.URI.create(pb.contains("://") ? pb : "http://" + pb).getHost(); } catch (Exception ignored) { }
        }
        this.previewHost = ph == null ? "" : ph.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String host = Optional.ofNullable(req.getHeader("Host")).orElse("");
        String uri = URLDecoder.decode(req.getRequestURI(), StandardCharsets.UTF_8);
        Matcher h = hostPattern.matcher(host);
        // 预览域名（preview.zhiye…）也落在发布站点泛域名 *.zhiye… 里：不能当成 slug=preview 的应用站点
        boolean previewDomain = !previewHost.isEmpty() && host.replaceFirst(":\\d+$", "").equalsIgnoreCase(previewHost);
        if (h.matches() && !previewDomain) {
            String slug = h.group(1).toLowerCase();
            // 发布站点同源的平台接口：数据源代理与 SDK（页面里不放令牌）
            if (uri.startsWith("/__zhiye/")) { servePlatform(slug, uri, req, res, chain); return; }
            var found = apps.findBySlug(slug);
            if (found.isPresent() && found.get().isService()) {
                servePublishedService(found.get(), uri, req, res);
                return;
            }
            if (!isRead(req)) { res.sendError(405); return; }
            servePublished(slug, uri, req, res);
            return;
        }
        Matcher p = PREVIEW.matcher(uri);
        if (p.matches()) {
            if (!isRead(req)) { res.sendError(405); return; }
            String as = req.getParameter("__as");
            servePreview(p.group(1), p.group(2) == null ? "/" : p.group(2), res, "HEAD".equals(req.getMethod()),
                    as != null && CloudService.ROLES.contains(as) ? as : "admin");
            return;
        }
        Matcher t = TEMPLATE.matcher(uri);
        if (t.matches()) {
            if (!isRead(req)) { res.sendError(405); return; }
            String prefix = jdbc.sql("select preview_prefix from template where id = ? and preview_prefix is not null").param(t.group(1)).query(String.class).optional().orElse(null);
            if (prefix == null) { notFound(res, "这个作品没有页面预览"); return; }
            serve(prefix, null, t.group(2) == null ? "/" : t.group(2), res, "HEAD".equals(req.getMethod()), false, null, null);
            return;
        }
        if (uri.equals("/") && !uri.startsWith("/api")) {
            // 站点网关根路径：健康检查
            res.setContentType("text/plain; charset=utf-8");
            res.getWriter().write("zhiye site gateway");
            return;
        }
        chain.doFilter(req, res);
    }

    private static boolean isRead(HttpServletRequest req) { return "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod()); }

    private void servePreview(String buildId, String path, HttpServletResponse res, boolean head, String role) throws IOException {
        var build = versions.build(buildId);
        if (build.isEmpty()) { notFound(res, "预览不存在或已过期"); return; }
        // buildId 显式传入：previewPrefix 的最后一段是 "preview"，不能靠它反推
        serve(build.get().previewPrefix(), buildId, path, res, head, true, null, role);
    }

    private void servePublished(String slug, String path, HttpServletRequest req, HttpServletResponse res) throws IOException {
        boolean head = "HEAD".equals(req.getMethod());
        String userAgent = req.getHeader("User-Agent");
        var app = apps.findBySlug(slug);
        if (app.isEmpty() || app.get().publishedVersionId() == null) { notFound(res, "这个站点不存在或已下线"); return; }
        String appId = app.get().id();
        // 访问级别：员工登录 / 指定人员的应用，没有站点会话先去统一登录，回来后再看页面；静态资源也一样受保护
        AccessService.Access acc = access.get(appId);
        SsoUser user = access.siteUser(appId, req).orElse(null);
        if (acc.needsLogin() && user == null) {
            if (ContentTypes.hasExtension(path) && !path.endsWith(".html")) { res.sendError(401); return; }
            String q = req.getQueryString();
            res.setHeader("Cache-Control", "no-store");
            res.sendRedirect(loginStart(appId, path + (q == null ? "" : "?" + q)));
            return;
        }
        if (!access.canVisit(appId, acc, user)) {
            message(res, 403, "没有访问权限", "这个应用只对指定人员开放，你（" + user.loginName() + "）还不在名单里，请联系应用作者添加。");
            return;
        }
        var v = versions.find(app.get().publishedVersionId());
        var build = v.flatMap(x -> versions.build(x.buildId()));
        if (build.isEmpty()) { notFound(res, "站点内容缺失"); return; }
        if (acc.needsLogin()) res.setHeader("Cache-Control", "private, no-cache");
        // 发布站点不放令牌：云数据库、存储、数据源都走同源 /__zhiye/*，身份来自站点会话
        boolean cloudOn = cloud.isOn(appId);
        String inject = cloudOn || user != null || acc.needsLogin() || sources.hasBindings(appId) || ai.usesAi(build.get().publishPrefix()) || ai.buildUses(build.get().publishPrefix(), "zhiye.fn")
                ? "<script>window.__ZHIYE_CLOUD__=" + Json.write(siteConfig(appId, cloudOn, access.userView(appId, user))) + ";</script>"
                  + "<script src=\"/__zhiye/sdk.js\"></script>"
                : null;
        String served = serve(build.get().publishPrefix(), null, path, res, head, false, inject, null);
        // 只统计已发布站点的 HTML 文档（不含预览、静态资源、HEAD 和爬虫）
        if (served != null && !head && (served.endsWith(".html") || served.endsWith(".htm")) && !PageViewRecorder.isBot(userAgent))
            pageViews.record(app.get().id());
    }

    /** 发布态 / 工作台隔离域预览态单体后端应用代理：支持全部 HTTP 方法、华星 SSO 鉴权与身份头透传、闲置自动唤醒与 PV 统计。 */
    private void servePublishedService(AppRow app, String path, HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (serviceProxy == null) { message(res, 503, "单体服务网关未启用", "当前环境未启用单体应用托管服务。"); return; }
        String appId = app.id();
        boolean previewAuthorized = isAuthorizedServicePreview(appId, req, res);
        if (!previewAuthorized && app.publishedVersionId() == null) { notFound(res, "这个站点不存在或已下线"); return; }
        SsoUser user = access.siteUser(appId, req).orElse(null);
        if (!previewAuthorized) {
            AccessService.Access acc = access.get(appId);
            if (acc.needsLogin() && user == null) {
                boolean apiLike = path.startsWith("/api/") || !"GET".equalsIgnoreCase(req.getMethod())
                        || (ContentTypes.hasExtension(path) && !path.endsWith(".html"));
                if (apiLike) { res.sendError(401); return; }
                String q = req.getQueryString();
                res.setHeader("Cache-Control", "no-store");
                res.sendRedirect(loginStart(appId, path + (q == null ? "" : "?" + q)));
                return;
            }
            if (!access.canVisit(appId, acc, user)) {
                message(res, 403, "没有访问权限", "这个应用只对指定人员开放，你（" + user.loginName() + "）还不在名单里，请联系应用作者添加。");
                return;
            }
        }
        boolean isAdmin = previewAuthorized || (user != null && access.isAdmin(appId, user));
        // 仅当携带合法作者预览令牌（previewAuthorized）时切到预览槽位；普通访客（含被外站 iframe 嵌入）严格访问线上已发布槽位
        String ct = serviceProxy.proxy(app, path, req, res, user, isAdmin, previewAuthorized);
        if (!previewAuthorized && "GET".equalsIgnoreCase(req.getMethod()) && ct != null && ct.toLowerCase().contains("text/html")
                && !PageViewRecorder.isBot(req.getHeader("User-Agent"))) {
            pageViews.record(appId);
        }
    }

    private boolean isAuthorizedServicePreview(String appId, HttpServletRequest req, HttpServletResponse res) {
        if (serviceCrypto == null) return false;
        // 严禁调用 req.getParameter()，否则 Tomcat 会消费 application/x-www-form-urlencoded 的 POST 请求体
        String qToken = queryParam(req.getQueryString(), "_zhiye_preview");
        if (qToken != null && serviceCrypto.verifyPreviewToken(appId, qToken)) {
            String sameSite = req.isSecure() ? "SameSite=None; Secure" : "SameSite=Lax";
            res.addHeader("Set-Cookie", "zhiye_svc_preview=" + qToken + "; Path=/; HttpOnly; " + sameSite);
            return true;
        }
        jakarta.servlet.http.Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie c : cookies) {
                if ("zhiye_svc_preview".equals(c.getName()) && serviceCrypto.verifyPreviewToken(appId, c.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String queryParam(String queryString, String key) {
        if (queryString == null || queryString.isEmpty()) return null;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(k)) {
                String rawVal = eq < 0 ? "" : pair.substring(eq + 1);
                return URLDecoder.decode(rawVal, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Map<String, Object> siteConfig(String appId, boolean cloudOn, Map<String, Object> user) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("appId", appId);
        m.put("api", "/__zhiye");
        m.put("source", "/__zhiye/source");
        m.put("site", true);
        m.put("cloud", cloudOn);
        m.put("user", user);
        return m;
    }

    /** 平台（api 域）上的站点登录入口：已登录平台直接回来，否则先走统一认证 */
    private String loginStart(String appId, String next) {
        return props.publicBaseUrl().replaceAll("/+$", "") + "/api/auth/site/start?app=" + enc(appId) + "&next=" + enc(next);
    }

    private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** 只允许站内相对路径，防止开放重定向 */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.contains("\\") || next.startsWith("/__zhiye/")) return "/";
        return next;
    }

    /** 返回实际提供的文件（相对路径），404 时返回 null。inject：发布页注入的脚本；role：预览页的模拟身份 */
    private String serve(String prefix, String buildId, String path, HttpServletResponse res, boolean head, boolean immutableBuild,
                         String inject, String role) throws IOException {
        String rel = path.replaceFirst("^/+", "");
        if (rel.contains("..")) { notFound(res, "页面不存在"); return null; }
        if (rel.isEmpty() || rel.endsWith("/")) rel = rel + "index.html";
        Optional<InputStream> in = store.get(prefix + "/" + rel);
        if (in.isEmpty() && !ContentTypes.hasExtension(rel)) {
            rel = "index.html"; // 单页应用回退
            in = store.get(prefix + "/" + rel);
        }
        if (in.isEmpty()) { notFound(res, "页面不存在"); return null; }
        res.setStatus(200);
        res.setContentType(ContentTypes.of(rel));
        boolean html = rel.endsWith(".html") || rel.endsWith(".htm");
        if (html) res.setHeader("Cache-Control", "no-cache");
        else res.setHeader("Cache-Control", immutableBuild ? "public, max-age=31536000, immutable" : "public, max-age=300");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        // 工作台用 sandbox iframe（无 allow-same-origin）加载预览，页面来源是 null；
        // Vite 产物的 <script type="module" crossorigin> 需要 CORS 才能执行。内容是公开静态文件，不带凭据。
        res.setHeader("Access-Control-Allow-Origin", "*");
        // 预览页注入云服务令牌与 SDK：只在预览里，发布的站点不注入（令牌有时效）
        if (html && immutableBuild && path != null) {
            byte[] body;
            try (InputStream s = in.get()) { body = head ? new byte[0] : s.readAllBytes(); }
            if (!head) res.getOutputStream().write(withCloud(buildId, prefix, body, role));
            return rel;
        }
        if (html && inject != null) {
            byte[] body;
            try (InputStream s = in.get()) { body = head ? new byte[0] : s.readAllBytes(); }
            if (!head) res.getOutputStream().write(inject(body, inject));
            return rel;
        }
        try (InputStream s = in.get()) {
            if (!head) s.transferTo(res.getOutputStream());
        }
        return rel;
    }

    /** 预览页：注入应用令牌和一份极小的云服务 SDK（db + storage） */
    private byte[] withCloud(String buildId, String prefix, byte[] body, String role) {
        if (buildId == null) return body;
        // 注意：不能用 query(...).stream()——那个 Stream 不关闭会一直占着连接，预览请求一多就把池子耗干
        // 构建按应用隔离：只认 build.app_id，旧数据里共用的构建不注入（避免拿到别的应用的令牌）
        String appId = versions.buildApp(buildId).orElse(null);
        if (appId == null) return body;
        boolean enabled = jdbc.sql("select count(*) from app_cloud where app_id = ? and status = 'on'")
                .params(appId).query(Long.class).single() > 0;
        if (!enabled && !sources.hasBindings(appId) && !ai.usesAi(prefix) && !ai.buildUses(prefix, "zhiye.fn")) return body;
        // publicBaseUrl 结尾可能有斜杠，统一去掉；预览页在站点域，/cloud/* 在 api 域，必须用绝对地址
        String base = props.publicBaseUrl().replaceAll("/+$", "");
        // /cloud/sdk.js 在加载时读取 window.__ZHIYE_CLOUD__，所以配置脚本必须排在它前面
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("appId", appId);
        cfg.put("api", base + "/cloud/" + appId);
        cfg.put("token", cloud.mint(appId, 1800, role));
        cfg.put("cloud", enabled);
        cfg.put("preview", role);
        var who = access.previewWho(appId, role);
        cfg.put("user", who.view());
        String script = "<script>window.__ZHIYE_CLOUD__=" + Json.write(cfg) + ";</script>"
                + "<script src=\"" + base + "/cloud/sdk.js\"></script>";
        return inject(body, script);
    }

    private static byte[] inject(byte[] body, String script) {
        String html = new String(body, StandardCharsets.UTF_8);
        int idx = html.toLowerCase().indexOf("</head>");
        if (idx < 0) return body;
        return (html.substring(0, idx) + script + html.substring(idx)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 发布站点的 /__zhiye/*：
     * - GET sdk.js
     * - auth/login、auth/finish（统一登录回来换站点会话）、auth/logout
     * - source/{key}、me、db/**、storage/**：核对访问级别和会话后交给 CloudDataController（身份放在请求属性里）
     */
    private void servePlatform(String slug, String uri, HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        if (uri.equals("/__zhiye/sdk.js") && isRead(req)) {
            var f = props.webDistPath().resolve("cloud/sdk.js");
            if (!java.nio.file.Files.isRegularFile(f)) { res.sendError(404); return; }
            res.setContentType("text/javascript; charset=utf-8");
            res.setHeader("Cache-Control", "public, max-age=300");
            if (!"HEAD".equals(req.getMethod())) java.nio.file.Files.copy(f, res.getOutputStream());
            return;
        }
        Optional<AppRow> app = apps.findBySlug(slug).filter(a -> a.publishedVersionId() != null);
        try {
            if (app.isEmpty()) throw com.tcl.csot.zhiye.exception.ApiException.notFound("站点");
            String appId = app.get().id();
            switch (uri) {
                case "/__zhiye/auth/login" -> { res.sendRedirect(loginStart(appId, safeNext(req.getParameter("next")))); return; }
                case "/__zhiye/auth/finish" -> {
                    var r = access.redeemCode(appId, req.getParameter("code"));
                    if (r.isEmpty()) { message(res, 400, "登录没有完成", "登录链接已失效，请回到应用首页重新打开。"); return; }
                    res.addHeader("Set-Cookie", access.siteCookie(access.createSession(appId, r.get().userId()), java.time.Duration.ofDays(AccessService.SESSION_DAYS)));
                    res.sendRedirect(safeNext(r.get().next()));
                    return;
                }
                case "/__zhiye/auth/logout" -> {
                    if (req.getCookies() != null) for (var c : req.getCookies())
                        if (AccessService.SITE_COOKIE.equals(c.getName())) access.deleteSession(c.getValue());
                    res.addHeader("Set-Cookie", access.siteCookie("", java.time.Duration.ZERO));
                    res.sendRedirect("/");
                    return;
                }
                default -> { }
            }
            AccessService.Access acc = access.get(appId);
            SsoUser user = access.siteUser(appId, req).orElse(null);
            if (acc.needsLogin() && user == null)
                throw new com.tcl.csot.zhiye.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "登录已过期，请刷新页面重新登录");
            if (!access.canVisit(appId, acc, user)) throw new com.tcl.csot.zhiye.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "你没有访问这个应用的权限");
            // 写操作只接受带自定义头的请求：别的网站跨域发不出来（需要预检，这里不响应预检），cookie 也就不会被冒用
            if (!isRead(req) && req.getHeader("X-Zhiye-Request") == null)
                throw com.tcl.csot.zhiye.exception.ApiException.validation("请通过 window.zhiye 调用");
            Matcher m = SOURCE.matcher(uri);
            if (m.matches()) {
                if (!"POST".equals(req.getMethod())) throw com.tcl.csot.zhiye.exception.ApiException.notFound("接口");
                String ct = Optional.ofNullable(req.getContentType()).orElse("");
                if (!ct.toLowerCase().startsWith("application/json"))
                    throw com.tcl.csot.zhiye.exception.ApiException.validation("请用 application/json 提交参数");
                byte[] body = req.getInputStream().readNBytes(64 * 1024 + 1);
                if (body.length > 64 * 1024) throw com.tcl.csot.zhiye.exception.ApiException.validation("参数太大");
                var json = body.length == 0 ? null : Json.M.readTree(body);
                var out = sources.query(appId, m.group(1), json == null ? null : json.get("params"), "site");
                res.setStatus(200);
                res.setContentType("application/json; charset=utf-8");
                res.getOutputStream().write(Json.write(out).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (uri.equals("/__zhiye/ai/chat")) {
                // 只有构建产物里确实调用了 zhiye.ai 的应用才开放，避免任何发布站点都能被拿来白用模型
                String pp = versions.find(app.get().publishedVersionId()).flatMap(x -> versions.build(x.buildId())).map(b -> b.publishPrefix()).orElse(null);
                if (!ai.usesAi(pp)) throw new com.tcl.csot.zhiye.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "AI_NOT_USED", "这个应用没有用到应用内 AI");
            } else if (uri.startsWith("/__zhiye/fn/")) {
                if (!"POST".equals(req.getMethod())) throw com.tcl.csot.zhiye.exception.ApiException.notFound("接口");
            } else if (!(uri.equals("/__zhiye/me") || uri.startsWith("/__zhiye/db/") || uri.equals("/__zhiye/storage") || uri.startsWith("/__zhiye/storage/")))
                throw com.tcl.csot.zhiye.exception.ApiException.notFound("接口");
            req.setAttribute(CloudDataController.ATTR_APP, appId);
            req.setAttribute(CloudDataController.ATTR_WHO, access.who(appId, user));
            chain.doFilter(req, res);
        } catch (com.tcl.csot.zhiye.exception.ApiException e) {
            json(res, e.status().value(), Map.of("code", e.code(), "message", e.getMessage()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            json(res, 400, Map.of("code", "VALIDATION", "message", "参数不是合法的 JSON"));
        }
    }

    private static void json(HttpServletResponse res, int status, Object body) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json; charset=utf-8");
        res.getOutputStream().write(Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private void message(HttpServletResponse res, int status, String title, String text) throws IOException {
        res.setStatus(status);
        res.setContentType("text/html; charset=utf-8");
        res.setHeader("Cache-Control", "no-store");
        res.getOutputStream().write(("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>" + title + "</title>"
                + "<style>body{font-family:system-ui;display:grid;place-items:center;min-height:90vh;color:#3b3550;background:#f7f5fc}"
                + "h1{font-size:22px;margin:0 0 8px}p{color:#8a84a3;max-width:420px;line-height:1.6}a{color:#6d4aff}</style></head><body><div><h1>" + title
                + "</h1><p>" + org.springframework.web.util.HtmlUtils.htmlEscape(text) + "</p><p><a href=\"/__zhiye/auth/logout\">换个账号</a></p></div></body></html>")
                .getBytes(StandardCharsets.UTF_8));
    }

    private void notFound(HttpServletResponse res, String message) throws IOException {
        res.setStatus(404);
        res.setContentType("text/html; charset=utf-8");
        res.setHeader("Cache-Control", "no-cache");
        res.getOutputStream().write(("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>页面不存在</title>"
                + "<style>body{font-family:system-ui;display:grid;place-items:center;min-height:90vh;color:#3b3550;background:#f7f5fc}"
                + "h1{font-size:28px;margin:0 0 8px}p{color:#8a84a3}</style></head><body><div><h1>404</h1><p>" + message
                + "</p></div></body></html>").getBytes(StandardCharsets.UTF_8));
    }
}
