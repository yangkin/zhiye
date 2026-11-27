package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.importer.ImportService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.app.AssetService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
@RequestMapping("/api")
public class ImportController {
    private final ImportService imports;
    private final AssetService assets;
    private final CurrentPrincipal principal;

    public ImportController(ImportService imports, AssetService assets, CurrentPrincipal principal) {
        this.imports = imports;
        this.assets = assets;
        this.principal = principal;
    }

    @PostMapping(value = "/imports/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestPart(value = "file", required = false) MultipartFile file,
                                      @RequestParam(value = "folderName", required = false) String folderName) throws Exception {
        return imports.upload(principal.workspaceId(), file, folderName);
    }

    @PostMapping("/imports")
    public Map<String, Object> create(@RequestBody JsonNode body, @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        return imports.create(principal.workspaceId(), body, key);
    }

    @PostMapping(value = "/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadAsset(@RequestPart(value = "file", required = false) MultipartFile file) throws Exception {
        return assets.upload(principal.workspaceId(), file);
    }

    @GetMapping("/assets")
    public List<Map<String, Object>> listAssets() { return assets.list(principal.workspaceId()); }

    @GetMapping("/assets/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id,
                                          @RequestParam(defaultValue = "false") boolean download) throws Exception {
        var c = assets.content(principal.workspaceId(), id);
        var disp = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(c.name(), StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disp)
                .header("X-Content-Type-Options", "nosniff")
                .body(c.data());
    }
}
