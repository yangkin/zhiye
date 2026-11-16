package com.tcl.csot.zhiye.util;

import java.util.Locale;
import java.util.Map;

public final class ContentTypes {
    private ContentTypes() {}

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"), Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"), Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"), Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"), Map.entry("otf", "font/otf"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"), Map.entry("pdf", "application/pdf"), Map.entry("xml", "application/xml"),
            Map.entry("wasm", "application/wasm"), Map.entry("webmanifest", "application/manifest+json"));

    public static String of(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash) return "application/octet-stream";
        return TYPES.getOrDefault(path.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    public static boolean hasExtension(String path) {
        int dot = path.lastIndexOf('.'), slash = path.lastIndexOf('/');
        return dot > slash;
    }
}
