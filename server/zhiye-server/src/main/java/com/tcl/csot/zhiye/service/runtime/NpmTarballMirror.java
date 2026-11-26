package com.tcl.csot.zhiye.service.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.util.Json;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NpmTarballMirror {
    private static final Set<String> PUBLIC_HOSTS = Set.of("registry.npmjs.org", "registry.npmmirror.com");
    private static final Pattern TARBALL_PATH = Pattern.compile("^/(.+)/-/([^/]+\\.tgz)$");

    private NpmTarballMirror() {}

    static int rewriteLocks(Path sourceDir, String cdn) throws IOException {
        if (cdn == null || cdn.isBlank() || sourceDir == null || !Files.isDirectory(sourceDir)) return 0;
        String base = cdn.trim().replaceAll("/+$", "");
        int changed = 0;
        try (var stream = Files.walk(sourceDir)) {
            for (Path lock : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(lock) && "package-lock.json".equals(lock.getFileName().toString())) {
                    changed += rewriteLock(lock, base);
                }
            }
        }
        return changed;
    }

    private static int rewriteLock(Path lock, String cdn) throws IOException {
        JsonNode root = Json.M.readTree(Files.readString(lock, StandardCharsets.UTF_8));
        int changed = rewriteNode(root, cdn);
        if (changed > 0) {
            Files.writeString(lock, Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
        }
        return changed;
    }

    private static int rewriteNode(JsonNode node, String cdn) {
        if (node instanceof ObjectNode object) {
            int changed = 0;
            JsonNode resolved = object.get("resolved");
            if (resolved != null && resolved.isTextual()) {
                String rewritten = rewriteUrl(resolved.asText(), cdn);
                if (!rewritten.equals(resolved.asText())) {
                    object.put("resolved", rewritten);
                    changed++;
                }
            }
            for (JsonNode child : object) changed += rewriteNode(child, cdn);
            return changed;
        }
        if (node instanceof ArrayNode array) {
            int changed = 0;
            for (JsonNode child : array) changed += rewriteNode(child, cdn);
            return changed;
        }
        return 0;
    }

    private static String rewriteUrl(String value, String cdn) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || !PUBLIC_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) return value;
            Matcher match = TARBALL_PATH.matcher(uri.getPath());
            if (!match.matches()) return value;
            String name = match.group(1);
            String file = match.group(2);
            String baseName = name.substring(name.lastIndexOf('/') + 1);
            String prefix = baseName + "-";
            if (!file.startsWith(prefix) || !file.endsWith(".tgz")) return value;
            String version = file.substring(prefix.length(), file.length() - 4);
            if (name.isBlank() || version.isBlank()) return value;
            return cdn + "/" + name + "/" + version + "/" + file;
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }
}
