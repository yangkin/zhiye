package com.tcl.csot.zhiye.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** 对象存储抽象：本地开发用文件系统，生产用 S3 兼容存储。 */
public interface ObjectStore {
    void put(String key, Path file) throws IOException;

    void put(String key, byte[] data) throws IOException;

    /** 不存在时返回 empty */
    Optional<InputStream> get(String key) throws IOException;

    boolean exists(String key) throws IOException;

    /** 列出前缀下的所有键 */
    List<String> list(String prefix) throws IOException;

    void deletePrefix(String prefix) throws IOException;

    /** 删除一个键（不存在时什么也不做） */
    void delete(String key) throws IOException;

    default Optional<byte[]> getBytes(String key) throws IOException {
        Optional<InputStream> in = get(key);
        if (in.isEmpty()) return Optional.empty();
        try (InputStream s = in.get()) { return Optional.of(s.readAllBytes()); }
    }

    /** 把目录下全部文件上传到 prefix/ 下 */
    default int putDirectory(String prefix, Path dir) throws IOException {
        int[] n = {0};
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                put(prefix + "/" + rel, p);
                n[0]++;
            }
        }
        return n[0];
    }
}
