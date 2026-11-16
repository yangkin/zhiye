package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.service.cloud.CloudPolicy;
import com.tcl.csot.zhiye.service.cloud.CloudService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 云服务数据面，两种入口共用：
 * - 预览：/cloud/{appId}/**，带应用令牌（`Authorization: Bearer zt....`），身份由令牌里的角色决定（作者 / 模拟员工 / 模拟未登录）
 * - 发布站点：同源 /__zhiye/**，不放令牌；SiteFilter 按域名找到应用、核对访问级别和站点会话后把应用和身份放进请求属性
 */
@RestController
@RequestMapping({"/cloud/{appId}", "/__zhiye"})
public class CloudDataController {
    /** 值和列类型对不上、非空约束等：返回 400 和中文说明，应用可以直接提示用户 */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> badData(org.springframework.dao.DataAccessException e) {
        ApiException bad = CloudService.badValue(e);
        if (bad == null) throw e;
        return ResponseEntity.badRequest().body(Map.of("code", bad.code(), "message", bad.getMessage()));
    }

    public static final String ATTR_APP = "zhiye.site.app";
    public static final String ATTR_WHO = "zhiye.site.who";

    private final CloudService cloud;
    private final AccessService access;

    public CloudDataController(CloudService cloud, AccessService access) {
        this.cloud = cloud;
        this.access = access;
    }

    record Ctx(CloudService.Cloud cloud, CloudPolicy.Who who) {}

    private Ctx ctx(String appId, HttpServletRequest req) {
        if (req.getAttribute(ATTR_APP) instanceof String siteApp && appId == null)
            return new Ctx(cloud.forSite(siteApp), (CloudPolicy.Who) req.getAttribute(ATTR_WHO));
        if (appId == null) throw ApiException.notFound("应用");
        String header = req.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        CloudService.Bound b = cloud.authBound(token);
        if (!b.cloud().appId().equals(appId)) throw ApiException.notFound("应用");
        return new Ctx(b.cloud(), access.previewWho(appId, b.role()));
    }

    /** 当前身份：未登录为 null */
    @GetMapping("/me")
    public Map<String, Object> me(@PathVariable(required = false) String appId, HttpServletRequest req) {
        var who = ctx(appId, req).who();
        return java.util.Collections.singletonMap("user", who == null ? null : who.view());
    }

    // ---------- 数据库 ----------
    @GetMapping("/db/{table}")
    public Map<String, Object> list(@PathVariable(required = false) String appId, @PathVariable String table,
                                    @RequestParam Map<String, String> query, HttpServletRequest req) {
        Ctx c = ctx(appId, req);
        return cloud.list(c.cloud(), table, query, c.who());
    }

    @PostMapping("/db/{table}")
    public Map<String, Object> insert(@PathVariable(required = false) String appId, @PathVariable String table,
                                      @RequestBody JsonNode body, HttpServletRequest req) {
        Ctx c = ctx(appId, req);
        return cloud.insert(c.cloud(), table, body, c.who());
    }

    @PatchMapping("/db/{table}/{id}")
    public Map<String, Object> update(@PathVariable(required = false) String appId, @PathVariable String table, @PathVariable long id,
                                      @RequestBody JsonNode body, HttpServletRequest req) {
        Ctx c = ctx(appId, req);
        return cloud.update(c.cloud(), table, id, body, c.who());
    }

    @DeleteMapping("/db/{table}/{id}")
    public ResponseEntity<Void> delete(@PathVariable(required = false) String appId, @PathVariable String table, @PathVariable long id,
                                       HttpServletRequest req) {
        Ctx c = ctx(appId, req);
        cloud.delete(c.cloud(), table, id, c.who());
        return ResponseEntity.noContent().build();
    }

    /** 原子加减：{column, delta, min?, max?} */
    @PostMapping("/db/{table}/{id}/increment")
    public Map<String, Object> increment(@PathVariable(required = false) String appId, @PathVariable String table, @PathVariable long id,
                                         @RequestBody JsonNode body, HttpServletRequest req) {
        Ctx c = ctx(appId, req);
        return cloud.increment(c.cloud(), table, id, body, c.who());
    }

    /** 聚合：{groupBy, count, sum, avg, min, max, where} */
    @PostMapping("/db/{table}/aggregate")
    public Map<String, Object> aggregate(@PathVariable(required = false) String appId, @PathVariable String table,
                                         @RequestBody(required = false) JsonNode body, HttpServletRequest req) {
        Ctx c = ctx(appId, req);
        return Map.of("rows", cloud.aggregate(c.cloud(), table, body, c.who()));
    }

    // ---------- 文件存储 ----------
    @PostMapping("/storage")
    public Map<String, Object> upload(@PathVariable(required = false) String appId, @RequestParam String path,
                                      @RequestBody byte[] body, HttpServletRequest req) throws Exception {
        return cloud.putFile(ctx(appId, req).cloud(), path, body);
    }

    @PostMapping(value = "/storage/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadMultipart(@PathVariable(required = false) String appId,
                                               @RequestPart("file") MultipartFile file, HttpServletRequest req) throws Exception {
        Ctx c = ctx(appId, req);
        String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        return cloud.putFile(c.cloud(), name, file.getBytes());
    }

    @GetMapping(value = "/storage", params = "path")
    public ResponseEntity<byte[]> download(@PathVariable(required = false) String appId, @RequestParam String path,
                                           HttpServletRequest req) throws Exception {
        var bytes = cloud.readFile(ctx(appId, req).cloud(), path);
        if (bytes.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes.get());
    }

    @GetMapping("/storage")
    public List<String> files(@PathVariable(required = false) String appId, HttpServletRequest req) throws Exception {
        return cloud.listFiles(ctx(appId, req).cloud());
    }
}
