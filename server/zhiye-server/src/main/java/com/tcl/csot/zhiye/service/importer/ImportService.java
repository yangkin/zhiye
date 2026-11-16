package com.tcl.csot.zhiye.service.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.service.app.Catalog;
import com.tcl.csot.zhiye.service.app.Views;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.service.task.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ImportService {
    private final ObjectStore store;
    private final JdbcClient jdbc;
    private final ZhiyeProperties props;
    private final AppRepository apps;
    private final TaskService taskService;
    private final GitService git;
    private final Views views;
    private final com.tcl.csot.zhiye.service.workspace.QuotaService quota;

    public ImportService(ObjectStore store, JdbcClient jdbc, ZhiyeProperties props, AppRepository apps, TaskService taskService,
                         GitService git, Views views, com.tcl.csot.zhiye.service.workspace.QuotaService quota) {
        this.store = store;
        this.jdbc = jdbc;
        this.props = props;
        this.apps = apps;
        this.taskService = taskService;
        this.git = git;
        this.views = views;
        this.quota = quota;
    }

    private ImportInspector.Limits limits() {
        var i = props.importer();
        return new ImportInspector.Limits(i.maxUploadBytes(), i.maxFiles(), i.maxUnpackedBytes(), i.maxFileBytes());
    }

    public Map<String, Object> upload(String ws, MultipartFile file, String folderName) throws Exception {
        if (file == null || file.isEmpty()) throw ImportInspector.invalid("请选择一个文件");
        String original = file.getOriginalFilename() == null ? "upload" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String lower = original.toLowerCase();
        if (file.getSize() > props.importer().maxUploadBytes())
            throw ImportInspector.invalid("文件太大：最多 " + (props.importer().maxUploadBytes() >> 20) + " MB");
        String format;
        ImportInspector.Result r;
        Path tmp = Files.createTempFile("zhiye-upload", ".bin");
        try {
            file.transferTo(tmp);
            if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                format = "html";
                r = ImportInspector.inspectHtml(Files.readAllBytes(tmp), limits());
            } else if (lower.endsWith(".zip")) {
                format = "zip";
                r = ImportInspector.inspectZip(tmp, limits());
            } else {
                throw ImportInspector.invalid("只支持 .html、.htm、.zip 文件或项目文件夹");
            }
            String id = Ids.newId("up");
            String key = "uploads/" + id + "/" + ("html".equals(format) ? "index.html" : "archive.zip");
            store.put(key, tmp);
            String name = folderName != null && !folderName.isBlank() ? folderName.trim() : original.replaceFirst("\\.(html?|zip)$", "");
            if (name.length() > 40) name = name.substring(0, 40);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("uploadId", id);
            manifest.put("name", name);
            manifest.put("format", format);
            manifest.put("framework", r.framework());
            manifest.put("deployable", r.deployable());
            manifest.put("unsupportedReason", r.unsupportedReason());
            manifest.put("fileCount", r.fileCount());
            manifest.put("totalSize", r.totalSize());
            List<Map<String, Object>> files = r.files().stream().limit(500)
                    .map(f -> Map.<String, Object>of("path", f.path(), "size", f.size())).toList();
            manifest.put("files", files);
            manifest.put("rootDir", r.rootDir());
            manifest.put("candidates", r.candidates());
            manifest.put("warnings", r.warnings());
            ObjectNode stored = Json.M.valueToTree(manifest);
            stored.put("projectKind", r.projectKind());
            jdbc.sql("insert into upload (id, workspace_id, object_key, file_name, format, folder_name, manifest) values (?,?,?,?,?,?,?::jsonb)")
                    .params(id, ws, key, original, format, folderName, Json.write(stored)).update();
            return manifest;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public Map<String, Object> create(String ws, JsonNode body, String idemKey) throws Exception {
        Optional<TaskRow> dup = taskService.byIdem(ws, idemKey);
        if (dup.isPresent()) {
            AppRow app = apps.findById(dup.get().appId()).orElseThrow();
            return Map.of("app", views.summary(app), "task", views.task(dup.get()));
        }
        String uploadId = body.path("uploadId").asText("");
        String name = body.path("name").asText("").trim();
        if (name.isEmpty() || name.length() > 40) throw ApiException.validation("应用名称需要 1～40 个字");
        String type = body.path("type").asText(Catalog.DEFAULT_TYPE);
        if (type.isBlank()) type = Catalog.DEFAULT_TYPE;
        if (!Catalog.TYPES.contains(type)) throw ApiException.validation(Catalog.TYPE_ERROR);
        var up = jdbc.sql("select object_key, format, folder_name, manifest from upload where id = ? and workspace_id = ?")
                .params(uploadId, ws).query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})
                .optional().orElseThrow(() -> ApiException.notFound("上传文件"));
        JsonNode manifest = Json.read(up[3]);
        if (!manifest.path("deployable").asBoolean())
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_UNSUPPORTED",
                    manifest.path("unsupportedReason").asText("暂不支持部署这个项目"));
        String rootDir = body.hasNonNull("rootDir") ? body.path("rootDir").asText() : manifest.path("rootDir").asText("");
        JsonNode candidate = null;
        for (JsonNode c : manifest.path("candidates")) if (rootDir.equals(c.path("dir").asText())) candidate = c;
        if (candidate == null && !manifest.has("candidates") && rootDir.isEmpty())
            candidate = Json.obj().put("dir", "").put("kind", manifest.path("projectKind").asText("static")).put("framework", manifest.path("framework").asText());
        if (candidate == null) throw ApiException.validation("选择的部署目录不在可部署的候选列表里");
        final String finalType = type;
        final JsonNode chosen = candidate;
        String prebuilt = null;
        for (JsonNode c : manifest.path("candidates"))
            if (c.path("prebuilt").asBoolean() && !rootDir.equals(c.path("dir").asText())) { prebuilt = c.path("dir").asText(); break; }
        final String prebuiltDir = prebuilt;
        return quota.withWorkspaceLock(ws, () -> createLocked(ws, up, chosen, prebuiltDir, uploadId, name, finalType, idemKey));
    }

    private Map<String, Object> createLocked(String ws, String[] up, JsonNode candidate, String prebuiltDir, String uploadId, String name, String type, String idemKey) {
        // 先校验上传和类型（IMPORT_UNSUPPORTED 等），再检查配额
        quota.checkCanCreateApp(ws);
        quota.checkCanStart(ws, true);
        String id = Ids.newId("app");
        String kind = candidate.path("kind").asText("static");
        String framework = candidate.path("framework").asText();
        AppRow row = new AppRow(id, ws, name, true, type, "导入的" + framework + "项目", "", "", "lavender", false, "适中", "auto",
                false, "generating", "import", framework, kind, false, Ids.slug(), List.of(), null, null, null, null, null);
        apps.insert(row);
        try { git.initEmpty(id); } catch (Exception e) { apps.softDelete(id); throw new IllegalStateException(e); }
        ObjectNode input = Json.obj();
        input.put("uploadId", uploadId);
        input.put("objectKey", up[0]);
        input.put("format", up[1]);
        if (up[2] != null) input.put("folderName", up[2]);
        input.put("projectKind", kind);
        input.put("rootDir", candidate.path("dir").asText(""));
        if (prebuiltDir != null && "vite".equals(kind)) input.put("prebuiltDir", prebuiltDir);
        TaskRow t;
        try {
            t = taskService.create(ws, id, "import", null, input, idemKey, 1, null);
        } catch (RuntimeException e) {
            apps.softDelete(id);
            git.deleteRepo(id);
            throw e;
        }
        return Map.of("app", views.summary(apps.findById(id).orElseThrow()), "task", views.task(t));
    }
}
