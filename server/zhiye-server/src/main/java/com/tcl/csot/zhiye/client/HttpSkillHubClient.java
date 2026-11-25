package com.tcl.csot.zhiye.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.SkillHubProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.ServerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SkillHub（fivestar-service aiToolbox）HTTP 客户端。
 * 接口：/{org}/aiToolbox/skills/market/query、/market/{skillId}、/my/query、/market/{skillId}/package/download、
 * /{skillId}/versions/{versionId}/package/download、/visible-scope/options、/add、/edit（edit 必带 draftJson）、
 * /{skillId}/versions/{versionId}/withdraw-review、/starClaw/review-state；
 * 分类：/{org}/skillCategory/category/queryAll。
 * 响应信封是 metis Result{code:"ok", data, success:true}；业务异常是 HTTP 200 + {code, message, type}。
 */
@Component
@ConditionalOnProperty(name = "zhiye.skillhub.enabled", havingValue = "true")
public class HttpSkillHubClient implements SkillHubClient {
    private static final Logger log = LoggerFactory.getLogger(HttpSkillHubClient.class);
    private static final String SKILLS = "/aiToolbox/skills";
    private static final String CATEGORIES = "/skillCategory/category/queryAll";
    private static final int MAX_PACKAGE = 50 * 1024 * 1024;

    private final SkillHubProperties props;
    private final ServerPort port;
    private final HttpClient http;

    public HttpSkillHubClient(SkillHubProperties props, ServerPort port) {
        this.props = props;
        this.port = port;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds()))).build();
    }

    private String base() { return url(props.baseUrl(), "zhiye.skillhub.base-url"); }

    private String fileBase() { return url(props.fileBaseUrl() == null || props.fileBaseUrl().isBlank() ? props.baseUrl() : props.fileBaseUrl(), "zhiye.skillhub.file-base-url"); }

    private String url(String u, String key) {
        if (u == null || u.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SKILLHUB_NOT_CONFIGURED", "SkillHub 地址没有配置（" + key + "）");
        return u.replace("{self}", port.selfBase()).replaceAll("/+$", "");
    }

    private String org() { return URLEncoder.encode(props.organizationId(), StandardCharsets.UTF_8); }

    private static String token(String token) {
        if (token == null || token.isBlank())
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_MISSING", "请先用公司统一账号登录");
        return token.trim();
    }

    // ---------- 查询 ----------

    @Override
    public List<Category> categories(String token) {
        // 平台市场页用 queryByCenter（业务域分类，与 SkillHub 门户一致）；空 centerName 由平台按用户中心解析。
        // 老环境没有该接口、拒绝空 centerName 或返回空时回退 queryAll，保证分类可用。
        try {
            JsonNode d = post(token, "/" + org() + "/skillCategory/category/queryByCenter",
                    Json.obj().put("centerName", props.centerName()));
            List<Category> out = parseCategories(d);
            if (!out.isEmpty()) return out;
            log.warn("SkillHub queryByCenter 返回空分类，回退 queryAll");
        } catch (Exception e) {
            log.warn("SkillHub queryByCenter 失败，回退 queryAll：{}", e.getMessage());
        }
        return parseCategories(get(token, "/" + org() + CATEGORIES));
    }

    private static List<Category> parseCategories(JsonNode d) {
        List<Category> out = new ArrayList<>();
        if (d != null && d.isArray()) for (JsonNode n : d) out.add(new Category(text(n, "id", "categoryId"), text(n, "name", "categoryName"), text(n, "description"), num(n, "skillCount")));
        return out;
    }

    @Override
    public Page<Item> market(String token, String keyword, String categoryId, int pageNo, int limit) {
        var body = Json.obj().put("pageNo", pageNo).put("limit", limit).put("includeBuiltin", false);
        if (keyword != null && !keyword.isBlank()) body.put("keyword", keyword.trim());
        if (categoryId != null && !categoryId.isBlank()) body.put("categoryId", categoryId);
        JsonNode d = post(token, "/" + org() + SKILLS + "/market/query", body);
        return page(d, HttpSkillHubClient::item);
    }

    @Override
    public Detail detail(String token, long skillId) {
        JsonNode d = get(token, "/" + org() + SKILLS + "/market/" + skillId);
        if (d == null || !d.isObject()) throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "这个技能不存在或你没有权限查看");
        Long sid = num(d, "skillId", "id");
        if (sid == null) throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "这个技能不存在或你没有权限查看");
        Item it = new Item(String.valueOf(sid), text(d, "slug"), text(d, "displayName", "name"), text(d, "summary", "description"), text(d, "owner"),
                text(d, "ownerUserName", "createdAccount"), text(d, "categoryId"), strings(d, "tags"), text(d, "currentVersion", "version"),
                num(d, "downCount", "downloads", "saveCount"), num(d, "favoriteCount", "favorites"), text(d, "riskLevel"),
                text(d, "visibleOrgNamePath"), text(d, "updatedAt", "updateDate", "lastUpdateDate"));
        List<FileEntry> files = new ArrayList<>();
        Long versionId = num(d, "currentVersionId", "versionId");
        if (versionId != null) {
            try {
                JsonNode tree = get(token, "/" + org() + SKILLS + "/" + sid + "/versions/" + versionId + "/files");
                collectFiles(tree, "", files);
            } catch (ApiException e) {
                log.debug("读取 SkillHub 文件树失败：{}", e.getMessage());
            }
        }
        List<VersionRow> versions = new ArrayList<>();
        try {
            JsonNode vs = get(token, "/" + org() + SKILLS + "/" + sid + "/versions?market=1");
            if (vs != null && vs.isArray()) for (JsonNode v : vs) {
                Long vid = num(v, "versionId", "id");
                if (vid == null) continue;
                versions.add(new VersionRow(vid, text(v, "version"), intOrNull(num(v, "reviewStatus")), text(v, "changelog", "changeLog"), num(v, "packageSize"), intOrNull(num(v, "fileCount"))));
            }
        } catch (ApiException e) {
            log.debug("读取 SkillHub 版本列表失败：{}", e.getMessage());
        }
        Boolean installed = d.path("installed").isBoolean() ? d.path("installed").asBoolean() : null;
        Boolean premium = d.path("premium").isBoolean() ? d.path("premium").asBoolean() : null;
        return new Detail(it, text(d, "skillMdContent", "skillMd", "content"), files, versionId, null, text(d, "categoryName"),
                num(d, "downCount", "downloads", "saveCount"), versions, installed, premium);
    }

    private static void collectFiles(JsonNode node, String prefix, List<FileEntry> out) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode n : node) collectFiles(n, prefix, out);
            return;
        }
        if (!node.isObject()) return;
        String path = text(node, "path", "name");
        boolean dir = node.path("directory").asBoolean(false) || node.path("isDirectory").asBoolean(false);
        if (path != null && !dir) {
            String full = prefix.isEmpty() ? path : prefix + "/" + path;
            Long size = num(node, "size", "fileSize");
            out.add(new FileEntry(full, size == null ? 0L : size.longValue()));
        }
        JsonNode children = node.path("children");
        if (children.isArray()) {
            String next = path != null && dir ? (prefix.isEmpty() ? path : prefix + "/" + path) : prefix;
            for (JsonNode c : children) collectFiles(c, next, out);
        }
    }

    @Override
    public Page<MySkill> mySkills(String token, String keyword, int pageNo, int limit) {
        var body = Json.obj().put("pageNo", pageNo).put("limit", limit);
        if (keyword != null && !keyword.isBlank()) body.put("keyword", keyword.trim());
        JsonNode d = post(token, "/" + org() + SKILLS + "/my/query", body);
        return page(d, HttpSkillHubClient::mySkill);
    }

    @Override
    public PackageFile packageFile(String token, long skillId, Long versionId) {
        String path = versionId == null
                ? "/" + org() + SKILLS + "/market/" + skillId + "/package/download"
                : "/" + org() + SKILLS + "/" + skillId + "/versions/" + versionId + "/package/download";
        HttpRequest req = HttpRequest.newBuilder(URI.create(fileBase() + path)).timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("Authorization", "Bearer " + token(token)).header("Accept", "application/zip").GET().build();
        HttpResponse<byte[]> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            log.warn("SkillHub 包下载失败：{}", e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UNAVAILABLE", "SkillHub 暂时无法访问，请稍后再试");
        }
        if (res.statusCode() == 401 || res.statusCode() == 403)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_INVALID", "平台登录已失效，请重新登录");
        if (res.statusCode() == 404) throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_PACKAGE_MISSING", "这个版本还没有可下载的技能包");
        if (res.statusCode() >= 400) throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_ERROR", "SkillHub 返回错误（HTTP " + res.statusCode() + "）");
        if (res.body().length > MAX_PACKAGE) throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_TOO_LARGE", "技能包超过 50MB，不能安装");
        String cd = res.headers().firstValue("Content-Disposition").orElse("");
        String name = "skill-" + skillId + ".zip";
        int i = cd.indexOf("filename=");
        if (i >= 0) name = cd.substring(i + 9).replace("\"", "").trim();
        return new PackageFile(name, res.body());
    }

    @Override
    public List<ScopeNode> scopeOptions(String token) {
        JsonNode d = get(token, "/" + org() + SKILLS + "/visible-scope/options");
        List<ScopeNode> out = new ArrayList<>();
        if (d != null && d.isArray()) for (JsonNode n : d) {
            String code = text(n, "orgCode", "code");
            if (code == null) continue;
            out.add(new ScopeNode(code, text(n, "orgName", "name"), n.path("level").asInt(1), text(n, "codePath"), text(n, "namePath")));
        }
        return out;
    }

    // ---------- 发布 ----------

    @Override
    public Submission publish(String token, PublishInput in, byte[] zip, String zipName) {
        UploadedPackage uploaded = uploadPackage(token, zip, zipName);
        String path = in.skillId() == null ? "/" + org() + SKILLS + "/add" : "/" + org() + SKILLS + "/edit";
        String boundary = "----zhiye-" + Ids.random(16);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            field(body, boundary, "categoryId", in.categoryId());
            field(body, boundary, "displayName", in.displayName());
            if (in.skillId() == null) field(body, boundary, "owner", in.owner());
            if (in.slug() != null) field(body, boundary, "slug", in.slug());
            field(body, boundary, "summary", in.summary());
            if (in.tags() != null) for (String t : in.tags()) field(body, boundary, "tags", t);
            field(body, boundary, "version", in.version());
            field(body, boundary, "subReview", Boolean.toString(in.submitReview()));
            field(body, boundary, "fileId", uploaded.fileId());
            field(body, boundary, "fileUrl", uploaded.fileUrl());
            if (in.visibleOrgCodePath() != null) field(body, boundary, "visibleOrgCodePath", in.visibleOrgCodePath());
            if (in.skillId() != null && in.skillId() > 0) {
                field(body, boundary, "skillId", String.valueOf(in.skillId()));
                var draft = Json.obj()
                        .put("displayName", in.displayName() == null ? "" : in.displayName())
                        .put("summary", in.summary() == null ? "" : in.summary())
                        .put("categoryId", in.categoryId() == null ? "" : in.categoryId())
                        .put("version", in.version() == null ? "" : in.version())
                        .put("changelog", in.changeLog() == null ? "" : in.changeLog())
                        .put("owner", in.owner() == null ? "" : in.owner());
                field(body, boundary, "draftJson", Json.write(draft));
            }
            if (in.skillId() != null && in.changeLog() != null && !in.changeLog().isBlank()) field(body, boundary, "changeLog", in.changeLog());
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path + "?includeReviewResult=true"))
                    .timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                    .header("Authorization", "Bearer " + token(token))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            JsonNode d = send(req, "提交技能");
            return new Submission(num(d, "skillId"), num(d, "versionId"), text(d, "submissionKey"), d.path("reused").asBoolean(false));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_ERROR", "SkillHub 暂时无法访问：" + e.getMessage());
        }
    }

    private record UploadedPackage(String fileId, String fileUrl) {}

    private UploadedPackage uploadPackage(String token, byte[] zip, String zipName) {
        if (zip == null || zip.length == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_PACKAGE_EMPTY", "技能包为空，不能发布");
        if (zip.length > MAX_PACKAGE) throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_TOO_LARGE", "技能包超过 50MB，不能发布");
        JsonNode cfg = post(token, "/" + org() + "/file/getFileStorageCfgInfo", null);
        String bucket = props.bucketName().isBlank() ? text(cfg, "bucketName") : props.bucketName();
        String storage = props.storageCode().isBlank() ? text(cfg, "storageCode") : props.storageCode();
        if (bucket == null || bucket.isBlank() || storage == null || storage.isBlank())
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UPLOAD_ERROR", "SkillHub 文件存储配置不完整");

        HttpRequest uuidReq = HttpRequest.newBuilder(URI.create(uploadBase() + "/" + org() + "/files/uuid"))
                .timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("Authorization", "Bearer " + token(token)).POST(HttpRequest.BodyPublishers.noBody()).build();
        String fileId = rawValue(sendRaw(uuidReq, "申请技能包上传标识"), "content", "data", "uuid");
        if (fileId == null || fileId.isBlank())
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UPLOAD_ERROR", "文件服务没有返回上传标识");

        String boundary = "----zhiye-upload-" + Ids.random(16);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            field(body, boundary, "bucketName", bucket);
            field(body, boundary, "storageCode", storage);
            field(body, boundary, "attachmentUUID", fileId);
            field(body, boundary, "relativePath", "");
            field(body, boundary, "packageName", zipName.replaceAll("\\.zip$", ""));
            field(body, boundary, "fileName", zipName);
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + safe(zipName) + "\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(zip);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest uploadReq = HttpRequest.newBuilder(URI.create(uploadBase() + "/" + org() + "/files/attachment/multipart"))
                    .timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                    .header("Authorization", "Bearer " + token(token))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
            String fileUrl = rawValue(sendRaw(uploadReq, "上传技能包"), "content", "data", "url", "fileUrl");
            if (fileUrl == null || fileUrl.isBlank())
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UPLOAD_ERROR", "文件服务没有返回技能包地址");
            return new UploadedPackage(fileId, fileUrl);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UPLOAD_ERROR", "技能包上传失败：" + e.getMessage());
        }
    }

    private String uploadBase() {
        if (props.uploadBaseUrl() != null && !props.uploadBaseUrl().isBlank())
            return url(props.uploadBaseUrl(), "zhiye.skillhub.upload-base-url");
        String base = base();
        if (base.endsWith("/fivestar-new")) return base.substring(0, base.length() - "/fivestar-new".length()) + "/tfle/v1";
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SKILLHUB_NOT_CONFIGURED", "SkillHub 文件上传地址没有配置（zhiye.skillhub.upload-base-url）");
    }

    @Override
    public ReviewState reviewState(String token, long skillId, String version) {
        JsonNode d = get(token, "/" + org() + SKILLS + "/starClaw/review-state?skillId=" + skillId + "&version=" + URLEncoder.encode(version, StandardCharsets.UTF_8));
        if (d == null || !d.isObject()) return null;
        return new ReviewState(d.path("inFlight").asBoolean(false), num(d, "skillId"), num(d, "versionId"), text(d, "version"),
                num(d, "reviewStatus") == null ? null : num(d, "reviewStatus").intValue(), num(d, "scanStatus") == null ? null : num(d, "scanStatus").intValue());
    }

    // ---------- HTTP ----------

    private void field(ByteArrayOutputStream body, String boundary, String name, String value) throws java.io.IOException {
        if (value == null) return;
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String safe(String s) { return s == null ? "skill.zip" : s.replace("\"", "_").replace("\r", "_").replace("\n", "_"); }

    private JsonNode get(String token, String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("Authorization", "Bearer " + token(token)).header("Accept", "application/json").GET().build();
        return send(req, "查询 SkillHub");
    }

    private JsonNode post(String token, String path, JsonNode body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("Authorization", "Bearer " + token(token)).header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        return send(req, "查询 SkillHub");
    }

    private String sendRaw(HttpRequest req, String what) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() == 401 || res.statusCode() == 403)
                throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_INVALID", "平台登录已失效，请重新登录");
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("{}失败：HTTP {} body={}", what, res.statusCode(), cut(res.body()));
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UPLOAD_ERROR", what + "失败（HTTP " + res.statusCode() + "）");
            }
            return res.body() == null ? "" : res.body().trim();
        } catch (java.io.IOException e) {
            log.warn("{}失败：{}", what, e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UNAVAILABLE", "SkillHub 文件服务暂时无法访问，请稍后再试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UNAVAILABLE", "SkillHub 文件服务暂时无法访问，请稍后再试");
        }
    }

    private static String rawValue(String body, String... keys) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode n = Json.read(body);
            if (n.isTextual()) return n.asText();
            if (n.isObject()) {
                String value = text(n, keys);
                if (value != null) return value;
            }
            return null;
        } catch (RuntimeException ignored) {
            return body;
        }
    }

    /** fivestar-service 返回 metis Result{code:"ok", data, success:true}；CommonException 走 HTTP 200 + {code, message, type}（HTTP 状态不可靠）；401/403 表示平台令牌失效 */
    private JsonNode send(HttpRequest req, String what) {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            log.warn("{}失败：{}", what, e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UNAVAILABLE", "SkillHub 暂时无法访问，请稍后再试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_UNAVAILABLE", "SkillHub 暂时无法访问，请稍后再试");
        }
        if (res.statusCode() == 401 || res.statusCode() == 403)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_INVALID", "平台登录已失效，请重新登录");
        if (res.statusCode() == 404) throw new ApiException(HttpStatus.NOT_FOUND, "SKILLHUB_NOT_FOUND", "SkillHub 接口不存在，请确认 fivestar-service 版本");
        JsonNode root;
        try {
            root = Json.read(res.body());
        } catch (RuntimeException e) {
            log.warn("{}返回非 JSON（HTTP {}）", what, res.statusCode());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_ERROR", "SkillHub 返回了无法识别的内容（HTTP " + res.statusCode() + "）");
        }
        // 业务异常：HTTP 200 + ExceptionResult{code, message, type}（metis GlobalExceptionHandler 对 CommonException 的返回）
        boolean businessError = root.hasNonNull("type") && root.hasNonNull("message") && !root.has("success");
        boolean ok = root.path("success").asBoolean(false);
        if (businessError || res.statusCode() >= 400 || !ok) {
            String msg = root.path("message").asText("");
            if (msg.isBlank()) msg = root.path("msg").asText("");
            log.warn("{}失败：HTTP {} code={} message={}", what, res.statusCode(), root.path("code").asText(""), cut(msg));
            if (msg.contains("skill.slug.exists")) throw new ApiException(HttpStatus.CONFLICT, "SKILL_SLUG_EXISTS", "这个技能的英文标识已被占用，请改一下技能名称后重试");
            if (msg.contains("not.owner")) throw new ApiException(HttpStatus.FORBIDDEN, "SKILL_NOT_OWNER", "只有技能的发布者能修改它");
            String shown = msg.isBlank() ? "HTTP " + res.statusCode() : cut(msg);
            throw new ApiException(res.statusCode() >= 400 && res.statusCode() < 500 ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                    "SKILLHUB_ERROR", "SkillHub 返回错误：" + shown);
        }
        return root.path("data");
    }

    // ---------- JSON 读取（字段名以 fivestar-service 的 DTO 为准，个别接口有出入时在这里兼容） ----------

    static Item item(JsonNode n) {
        if (n == null) return null;
        Long sid = num(n, "skillId", "id");
        if (sid == null) return null;
        return new Item(String.valueOf(sid), text(n, "slug"), text(n, "displayName", "name"), text(n, "summary", "description"), text(n, "owner"),
                text(n, "ownerUserName", "createdAccount"), text(n, "categoryId"), strings(n, "tags"), text(n, "currentVersion", "version"),
                num(n, "downCount", "downloads"), num(n, "favoriteCount", "favorites"), text(n, "riskLevel"),
                text(n, "visibleOrgNamePath"), text(n, "updatedAt", "lastUpdateDate"));
    }

    static MySkill mySkill(JsonNode n) {
        if (n == null) return null;
        Long sid = num(n, "skillId", "id");
        if (sid == null) return null;
        Long vid = num(n, "latestVersionId", "currentVersionId", "versionId");
        return new MySkill(String.valueOf(sid), text(n, "slug"), text(n, "displayName", "name"), text(n, "summary"), text(n, "categoryId"), text(n, "owner"),
                strings(n, "tags"), text(n, "latestVersion", "currentVersion", "version"), vid, text(n, "latestVersion", "currentVersion"),
                intOrNull(num(n, "status")), intOrNull(num(n, "latestReviewStatus", "reviewStatus")), n.path("hasDraft").asBoolean(false), intOrNull(num(n, "fileCount")),
                text(n, "lastUpdateDate", "updatedAt"));
    }

    private static <T> Page<T> page(JsonNode d, java.util.function.Function<JsonNode, T> map) {
        List<T> items = new ArrayList<>();
        JsonNode recs = d == null ? null : d.path("records");
        if (recs != null && recs.isArray()) for (JsonNode r : recs) {
            T v = map.apply(r);
            if (v != null) items.add(v);
        }
        long total = d == null ? items.size() : d.path("total").asLong(items.size());
        return new Page<>(items, total, d == null ? 1 : d.path("pageNo").asInt(1), d == null ? items.size() : d.path("limit").asInt(items.size()));
    }

    private static String text(JsonNode n, String... keys) {
        if (n == null) return null;
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
            if (v != null && v.isNumber()) return v.asText();
        }
        return null;
    }

    private static Long num(JsonNode n, String... keys) {
        if (n == null) return null;
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v == null || v.isNull()) continue;
            if (v.isNumber()) return v.longValue();
            if (v.isTextual() && !v.asText().isBlank()) {
                try { return Long.parseLong(v.asText().trim()); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private static Integer intOrNull(Long v) { return v == null ? null : v.intValue(); }

    private static List<String> strings(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        List<String> out = new ArrayList<>();
        if (v != null && v.isArray()) for (JsonNode x : v) if (x.isTextual()) out.add(x.asText());
        return out;
    }

    @Override
    public void setVisibleScope(String token, long skillId, long versionId, String visibleOrgCodePath) {
        var q = new StringBuilder("?visibleOrgCodePath=").append(URLEncoder.encode(visibleOrgCodePath, StandardCharsets.UTF_8));
        sendOk(token, "PUT", "/" + org() + SKILLS + "/" + skillId + "/versions/" + versionId + "/visible-scope" + q, null, "设置可见范围");
    }

    @Override
    public void withdraw(String token, long skillId, long versionId, String submissionKey) {
        String q = "?skillId=" + skillId + "&version=" + URLEncoder.encode(String.valueOf(versionId), StandardCharsets.UTF_8)
                + (submissionKey == null || submissionKey.isBlank() ? "" : "&submissionKey=" + URLEncoder.encode(submissionKey, StandardCharsets.UTF_8));
        sendOk(token, "POST", "/" + org() + SKILLS + "/" + skillId + "/versions/" + versionId + "/withdraw-review" + q, null, "撤回审核");
    }

    private void sendOk(String token, String method, String path, JsonNode body, String what) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .header("Authorization", "Bearer " + token(token));
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        send(b.build(), what);
    }

    private static String cut(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s; }
}
