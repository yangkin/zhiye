package com.tcl.csot.zhiye.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 本地文件系统对象存储（根目录 runtime/objects）。 */
public class LocalFsObjectStore implements ObjectStore {
    private final Path root;

    public LocalFsObjectStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("非法对象键");
        return p;
    }

    @Override
    public void put(String key, Path file) throws IOException {
        Path t = resolve(key);
        Files.createDirectories(t.getParent());
        Files.copy(file, t, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void put(String key, byte[] data) throws IOException {
        Path t = resolve(key);
        Files.createDirectories(t.getParent());
        Files.write(t, data);
    }

    @Override
    public Optional<InputStream> get(String key) throws IOException {
        Path p = resolve(key);
        if (!Files.isRegularFile(p)) return Optional.empty();
        return Optional.of(Files.newInputStream(p));
    }

    @Override
    public boolean exists(String key) { return Files.isRegularFile(resolve(key)); }

    @Override
    public List<String> list(String prefix) throws IOException {
        Path dir = resolve(prefix);
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> w = Files.walk(dir)) {
            w.filter(Files::isRegularFile).forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/')));
        }
        return out;
    }

    @Override
    public void delete(String key) throws IOException {
        Path f = resolve(key);
        if (Files.isRegularFile(f)) Files.delete(f);
    }

    @Override
    public void deletePrefix(String prefix) throws IOException {
        Path dir = resolve(prefix);
        if (!Files.exists(dir)) return;
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
