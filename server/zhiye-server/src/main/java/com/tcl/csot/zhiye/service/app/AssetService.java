package com.tcl.csot.zhiye.service.app;

import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.storage.ObjectStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class AssetService {
    private final JdbcClient jdbc;
    private final ObjectStore store;
    private final Views views;
    private final ZhiyeProperties props;

    public AssetService(JdbcClient jdbc, ObjectStore store, Views views, ZhiyeProperties props) {
        this.jdbc = jdbc;
        this.store = store;
        this.views = views;
        this.props = props;
    }

    public record Content(String name, String mime, byte[] data) {}

    public Map<String, Object> upload(String ws, MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) throw ApiException.validation("请选择一个文件");
        if (file.getSize() > props.importer().assetMaxBytes()) throw ApiException.validation("素材最多 10 MB");
        String name = file.getOriginalFilename() == null ? "file" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String mime = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String lower = name.toLowerCase();
        boolean ok = mime.startsWith("image/") || mime.equals("application/pdf") || mime.startsWith("text/")
                || mime.contains("officedocument")
                || lower.matches(".*\\.(png|jpe?g|gif|webp|svg|pdf|txt|md|markdown|csv|json|log|ya?ml|docx|xlsx|pptx)$");
        if (!ok) throw ApiException.validation("只支持图片、PDF、Word/Excel/PPT 和文本文件");
        if (lower.endsWith(".svg")) mime = "image/svg+xml";
        String id = Ids.newId("as");
        String key = "assets/" + ws + "/" + id;
        store.put(key, file.getBytes());
        jdbc.sql("insert into asset (id, workspace_id, object_key, mime, size, name) values (?,?,?,?,?,?)")
                .params(id, ws, key, mime, file.getSize(), name).update();
        return jdbc.sql("select * from asset where id = ?").param(id).query((rs, i) -> views.asset(rs)).single();
    }

    public List<Map<String, Object>> list(String ws) {
        return jdbc.sql("select * from asset where workspace_id = ? order by created_at desc").param(ws).query((rs, i) -> views.asset(rs)).list();
    }

    public Content content(String ws, String id) throws Exception {
        var row = jdbc.sql("select name, mime, object_key from asset where id = ? and workspace_id = ?").params(id, ws)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}).optional()
                .orElseThrow(() -> ApiException.notFound("素材"));
        byte[] data = store.getBytes(row[2]).orElseThrow(() -> ApiException.notFound("素材"));
        return new Content(row[0], row[1], data);
    }
}
