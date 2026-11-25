package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.util.ContentTypes;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

/** 托管前端构建产物（web/dist），非 /api、/llm 的路径回退到 index.html。 */
@RestController
@Profile("api")
public class SpaController {
    private final ZhiyeProperties props;
    private final ConcurrentHashMap<Path, CachedFile> cache = new ConcurrentHashMap<>();

    public SpaController(ZhiyeProperties props) { this.props = props; }

    @GetMapping({"/", "/**"})
    public ResponseEntity<?> serve(HttpServletRequest req) throws Exception {
        String uri = req.getRequestURI();
        if (uri.startsWith("/api/") || uri.equals("/api") || uri.startsWith("/llm/") || uri.startsWith("/internal/"))
            throw com.tcl.csot.zhiye.exception.ApiException.ofKey(HttpStatus.NOT_FOUND, "NOT_FOUND", "zhiye.error.api-not-found", "接口不存在");
        Path root = props.webDistPath();
        String rel = java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8).replaceFirst("^/+", "");
        Path file = root.resolve(rel).normalize();
        if (file.startsWith(root) && !rel.isEmpty() && Files.isRegularFile(file)) return file(req, rel, file);
        if (ContentTypes.hasExtension(rel) && rel.startsWith("assets/"))
            return ResponseEntity.status(404).header("Content-Type", "text/plain; charset=utf-8").body("Not Found".getBytes());
        Path index = root.resolve("index.html");
        if (!Files.isRegularFile(index))
            return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8")
                    .body("<!doctype html><meta charset=utf-8><title>织页</title><p>前端尚未构建：请先运行 npm run build -w web</p>".getBytes(StandardCharsets.UTF_8));
        return file(req, "index.html", index);
    }

    private ResponseEntity<byte[]> file(HttpServletRequest req, String rel, Path path) throws IOException {
        CachedFile cached = cached(path);
        boolean gzip = cached.gzip.length < cached.raw.length && acceptsGzip(req.getHeader(HttpHeaders.ACCEPT_ENCODING));
        byte[] body = gzip ? cached.gzip : cached.raw;
        var response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, ContentTypes.of(rel))
                .header(HttpHeaders.CACHE_CONTROL, rel.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-cache")
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING)
                .contentLength(body.length);
        if (gzip) response.header(HttpHeaders.CONTENT_ENCODING, "gzip");
        return response.body(body);
    }

    private CachedFile cached(Path path) throws IOException {
        try {
            return cache.compute(path, (p, old) -> {
                try {
                    long size = Files.size(p);
                    long modified = Files.getLastModifiedTime(p).toMillis();
                    if (old != null && old.size == size && old.modified == modified) return old;
                    byte[] raw = Files.readAllBytes(p);
                    return new CachedFile(size, modified, raw, gzip(p, raw));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static byte[] gzip(Path path, byte[] raw) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (raw.length < 1024 || !name.matches(".*\\.(?:js|css|html|json|svg|map|txt|xml)")) return raw;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 3));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(raw); }
        return out.toByteArray();
    }

    private static boolean acceptsGzip(String header) {
        if (header == null) return false;
        for (String part : header.toLowerCase(Locale.ROOT).split(",")) {
            String coding = part.trim();
            if ((coding.startsWith("gzip") || coding.startsWith("*")) && !coding.contains("q=0")) return true;
        }
        return false;
    }

    private record CachedFile(long size, long modified, byte[] raw, byte[] gzip) {}
}
