package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NpmTarballMirrorTest {
    @TempDir
    Path sourceDir;

    @Test
    void rewritesOnlyPublicNpmTarballsAndPreservesIntegrity() throws Exception {
        Path frontend = Files.createDirectories(sourceDir.resolve("frontend"));
        Path lock = frontend.resolve("package-lock.json");
        Files.writeString(lock, """
                {
                  "lockfileVersion": 3,
                  "packages": {
                    "node_modules/yallist": {
                      "version": "3.1.1",
                      "resolved": "https://registry.npmjs.org/yallist/-/yallist-3.1.1.tgz",
                      "integrity": "sha512-yallist"
                    },
                    "node_modules/@types/babel__template": {
                      "version": "7.4.4",
                      "resolved": "https://registry.npmmirror.com/@types/babel__template/-/babel__template-7.4.4.tgz",
                      "integrity": "sha512-babel"
                    },
                    "node_modules/private-lib": {
                      "resolved": "https://npm.csot.internal/private-lib/-/private-lib-1.0.0.tgz"
                    },
                    "node_modules/git-lib": {
                      "resolved": "git+https://github.com/example/git-lib.git#abc123"
                    }
                  }
                }
                """);

        int changed = NpmTarballMirror.rewriteLocks(sourceDir, "https://cdn.npmmirror.com/packages/");

        assertThat(changed).isEqualTo(2);
        var packages = Json.M.readTree(Files.readString(lock)).get("packages");
        assertThat(packages.get("node_modules/yallist").get("resolved").asText())
                .isEqualTo("https://cdn.npmmirror.com/packages/yallist/3.1.1/yallist-3.1.1.tgz");
        assertThat(packages.get("node_modules/yallist").get("integrity").asText()).isEqualTo("sha512-yallist");
        assertThat(packages.get("node_modules/@types/babel__template").get("resolved").asText())
                .isEqualTo("https://cdn.npmmirror.com/packages/@types/babel__template/7.4.4/babel__template-7.4.4.tgz");
        assertThat(packages.get("node_modules/@types/babel__template").get("integrity").asText()).isEqualTo("sha512-babel");
        assertThat(packages.get("node_modules/private-lib").get("resolved").asText())
                .isEqualTo("https://npm.csot.internal/private-lib/-/private-lib-1.0.0.tgz");
        assertThat(packages.get("node_modules/git-lib").get("resolved").asText())
                .isEqualTo("git+https://github.com/example/git-lib.git#abc123");
    }

    @Test
    void blankCdnLeavesLocksUntouched() throws Exception {
        Path lock = sourceDir.resolve("package-lock.json");
        String content = "{\"packages\":{\"x\":{\"resolved\":\"https://registry.npmjs.org/x/-/x-1.0.0.tgz\"}}}";
        Files.writeString(lock, content);

        assertThat(NpmTarballMirror.rewriteLocks(sourceDir, "")).isZero();
        assertThat(Files.readString(lock)).isEqualTo(content);
    }
}
