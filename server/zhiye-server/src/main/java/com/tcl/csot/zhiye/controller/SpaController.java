package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.util.ContentTypes;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 托管前端构建产物（web/dist），非 /api、/llm 的路径回退到 index.html。 */
@RestController
@Profile("api")
public class SpaController {
    private final ZhiyeProperties props;

    public SpaController(ZhiyeProperties props) { this.props = props; }

    @GetMapping({"/", "/**"})
    public ResponseEntity<?> serve(HttpServletRequest req) throws Exception {
        String uri = req.getRequestURI();
        if (uri.startsWith("/api/") || uri.equals("/api") || uri.startsWith("/llm/"))
            throw com.tcl.csot.zhiye.exception.ApiException.ofKey(HttpStatus.NOT_FOUND, "NOT_FOUND", "zhiye.error.api-not-found", "接口不存在");
        Path root = props.webDistPath();
        String rel = java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8).replaceFirst("^/+", "");
        Path file = root.resolve(rel).normalize();
        if (file.startsWith(root) && !rel.isEmpty() && Files.isRegularFile(file)) {
            String cache = rel.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-cache";
            return ResponseEntity.ok().header("Content-Type", ContentTypes.of(rel)).header("Cache-Control", cache).body(Files.readAllBytes(file));
        }
        if (ContentTypes.hasExtension(rel) && rel.startsWith("assets/"))
            return ResponseEntity.status(404).header("Content-Type", "text/plain; charset=utf-8").body("Not Found".getBytes());
        Path index = root.resolve("index.html");
        if (!Files.isRegularFile(index))
            return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8")
                    .body("<!doctype html><meta charset=utf-8><title>织页</title><p>前端尚未构建：请先运行 npm run build -w web</p>".getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8").header("Cache-Control", "no-cache").body(Files.readAllBytes(index));
    }
}
