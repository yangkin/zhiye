package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.service.cloud.CloudPolicy;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.service.cloud.FunctionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 数据控制台与访问设置（应用主人用，按工作空间鉴权）：
 * 访问级别 / 管理员名单、表数据增删改、导入导出、清空测试数据、权限预设、文件管理。
 * 控制台里的操作以管理员（作者）身份执行，仍然走字段规则和状态流转校验。
 */
@RestController
@RequestMapping("/api/apps/{appId}")
public class CloudConsoleController {
    /** 值和列类型对不上、非空约束等：返回 400 和中文说明，应用可以直接提示用户 */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> badData(org.springframework.dao.DataAccessException e) {
        ApiException bad = CloudService.badValue(e);
        if (bad == null) throw e;
        return ResponseEntity.badRequest().body(Map.of("code", bad.code(), "message", bad.getMessage()));
    }

    private final CloudService cloud;
    private final AccessService access;
    private final AppRepository apps;
    private final CurrentPrincipal principal;
    @org.springframework.beans.factory.annotation.Autowired
    private FunctionService fns;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.cloud.DatabaseQuotaService dbQuota;

    public CloudConsoleController(CloudService cloud, AccessService access, AppRepository apps, CurrentPrincipal principal) {
        this.cloud = cloud;
        this.access = access;
        this.apps = apps;
        this.principal = principal;
    }

    private AppRow app(String appId) {
        return apps.findById(appId).filter(a -> a.workspaceId().equals(principal.workspaceId()) && a.deletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("应用"));
    }

    private CloudService.Cloud c(String appId) { return cloud.forOwner(principal.workspaceId(), appId); }

    private CloudPolicy.Who admin(String appId) { return access.previewWho(appId, "admin"); }

    // ---------- 访问设置 ----------
    public record AccessBody(String level, List<String> members, List<String> admins) {}

    @GetMapping("/access")
    public Map<String, Object> getAccess(@PathVariable String appId) { return access.view(app(appId)); }

    @PutMapping("/access")
    public Map<String, Object> putAccess(@PathVariable String appId, @RequestBody AccessBody body) {
        AppRow a = app(appId);
        access.update(a, body.level(), body.members(), body.admins());
        return access.view(a);
    }

    // ---------- 数据控制台 ----------
    @GetMapping("/cloud/console")
    public Map<String, Object> overview(@PathVariable String appId) {
        var cl = c(appId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tables", cloud.tables(appId));
        m.put("counts", cloud.rowCounts(cl));
        m.put("presets", CloudPolicy.presetViews());
        m.put("access", access.get(appId).level());
        if (dbQuota != null) m.put("dbQuota", dbQuota.cloudAppQuotaView(appId, cl.schema()));
        return m;
    }

    @GetMapping("/cloud/console/{table}/rows")
    public Map<String, Object> rows(@PathVariable String appId, @PathVariable String table, @RequestParam Map<String, String> query) {
        return cloud.list(c(appId), table, query, admin(appId));
    }

    @PostMapping("/cloud/console/{table}/rows")
    public Map<String, Object> insert(@PathVariable String appId, @PathVariable String table, @RequestBody JsonNode body) {
        return cloud.insert(c(appId), table, body, admin(appId));
    }

    @PatchMapping("/cloud/console/{table}/rows/{id}")
    public Map<String, Object> update(@PathVariable String appId, @PathVariable String table, @PathVariable long id, @RequestBody JsonNode body) {
        return cloud.update(c(appId), table, id, body, admin(appId));
    }

    @DeleteMapping("/cloud/console/{table}/rows/{id}")
    public ResponseEntity<Void> delete(@PathVariable String appId, @PathVariable String table, @PathVariable long id) {
        cloud.delete(c(appId), table, id, admin(appId));
        return ResponseEntity.noContent().build();
    }

    /** 导入：{rows:[{列: 值}]}，浏览器里解析 CSV / Excel 并完成字段映射后提交；整批校验，一行不合规则全部不写 */
    @PostMapping("/cloud/console/{table}/import")
    public Map<String, Object> importRows(@PathVariable String appId, @PathVariable String table, @RequestBody JsonNode body) {
        return Map.of("inserted", cloud.insertMany(c(appId), table, body == null ? null : body.get("rows"), admin(appId)));
    }

    @GetMapping("/cloud/console/{table}/export")
    public ResponseEntity<byte[]> export(@PathVariable String appId, @PathVariable String table) {
        byte[] csv = cloud.exportCsv(c(appId), table);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(table + ".csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(csv);
    }

    @PostMapping("/cloud/console/{table}/clear")
    public Map<String, Object> clear(@PathVariable String appId, @PathVariable String table) {
        return Map.of("deleted", cloud.clear(c(appId), table));
    }

    public record PresetBody(String preset) {}

    @PutMapping("/cloud/console/{table}/preset")
    public Map<String, Object> preset(@PathVariable String appId, @PathVariable String table, @RequestBody PresetBody body) {
        c(appId);
        cloud.setPreset(appId, table, body.preset());
        return overview(appId);
    }

    @GetMapping("/cloud/console/functions")
    public Map<String, Object> functions(@PathVariable String appId) {
        app(appId);
        return fns.overview(appId);
    }

    @GetMapping("/cloud/console/files")
    public Map<String, Object> files(@PathVariable String appId) throws Exception {
        return Map.of("files", cloud.listFiles(c(appId)));
    }

    @DeleteMapping("/cloud/console/files")
    public ResponseEntity<Void> deleteFile(@PathVariable String appId, @RequestParam String path) throws Exception {
        cloud.deleteFile(c(appId), path);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cloud/console/files/download")
    public ResponseEntity<byte[]> download(@PathVariable String appId, @RequestParam String path) throws Exception {
        var bytes = cloud.readFile(c(appId), path);
        if (bytes.isEmpty()) return ResponseEntity.notFound().build();
        String name = path.substring(path.lastIndexOf('/') + 1);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes.get());
    }
}
