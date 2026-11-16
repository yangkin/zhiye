package com.tcl.csot.zhiye.service.importer;

import com.tcl.csot.zhiye.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportInspectorTest {
    static final ImportInspector.Limits LIMITS = new ImportInspector.Limits(50L << 20, 5000, 200L << 20, 20L << 20);

    @TempDir
    Path dir;

    Path zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        Path p = Files.createTempFile(dir, "t", ".zip");
        Files.write(p, bos.toByteArray());
        return p;
    }

    static Map<String, String> m(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
        return out;
    }

    @Test
    void staticSiteWithTopLevelFolderIsStripped() throws Exception {
        var r = ImportInspector.inspectZip(zip(m("site/index.html", "<h1>x</h1>", "site/about.html", "<p>a</p>",
                "site/node_modules/x/index.js", "x", "site/.DS_Store", "", "__MACOSX/site/._index.html", "")), LIMITS);
        assertThat(r.framework()).isEqualTo("静态站点");
        assertThat(r.deployable()).isTrue();
        assertThat(r.projectKind()).isEqualTo("static");
        assertThat(r.fileCount()).isEqualTo(2);
        assertThat(r.files()).extracting(ImportInspector.FileInfo::path).containsExactly("index.html", "about.html");
    }

    @Test
    void singleHtmlInZip() throws Exception {
        assertThat(ImportInspector.inspectZip(zip(m("index.html", "<h1>x</h1>", "style.css", "h1{}")), LIMITS).framework()).isEqualTo("HTML");
    }

    @Test
    void detectsFrameworks() throws Exception {
        assertThat(ImportInspector.inspectZip(zip(m("a/package.json", "{\"dependencies\":{\"next\":\"15\",\"react\":\"19\"}}")), LIMITS))
                .satisfies(r -> { assertThat(r.framework()).isEqualTo("Next.js"); assertThat(r.deployable()).isFalse(); assertThat(r.unsupportedReason()).contains("Next.js"); });
        assertThat(ImportInspector.inspectZip(zip(m("package.json", "{\"dependencies\":{\"nuxt\":\"3\"}}")), LIMITS).framework()).isEqualTo("Nuxt.js");
        var vite = ImportInspector.inspectZip(zip(m("v/package.json", "{\"dependencies\":{\"react\":\"19\"},\"devDependencies\":{\"vite\":\"7\"}}", "v/index.html", "<div id=root>")), LIMITS);
        assertThat(vite.framework()).isEqualTo("Vite + React");
        assertThat(vite.projectKind()).isEqualTo("vite");
        assertThat(ImportInspector.inspectZip(zip(m("package.json", "{\"dependencies\":{\"vue\":\"3\",\"vite\":\"7\"}}")), LIMITS).framework()).isEqualTo("Vite + Vue");
        assertThat(ImportInspector.inspectZip(zip(m("package.json", "{\"dependencies\":{\"react\":\"19\"}}")), LIMITS).framework()).isEqualTo("React");
        assertThat(ImportInspector.inspectZip(zip(m("app.py", "print(1)", "requirements.txt", "flask")), LIMITS).framework()).isEqualTo("Python");
        assertThat(ImportInspector.inspectZip(zip(m("go.mod", "module x")), LIMITS).framework()).isEqualTo("Go");
        assertThat(ImportInspector.inspectZip(zip(m("pom.xml", "<project/>")), LIMITS).framework()).isEqualTo("Java");
        assertThat(ImportInspector.inspectZip(zip(m("readme.md", "hi")), LIMITS).deployable()).isFalse();
    }

    @Test
    void rejectsUnsafeArchives() throws Exception {
        assertThatThrownBy(() -> ImportInspector.inspectZip(zip(m("../evil.html", "x", "index.html", "ok")), LIMITS))
                .isInstanceOf(ApiException.class).hasMessageContaining("不安全");
        assertThatThrownBy(() -> ImportInspector.inspectZip(zip(m("/abs.html", "x")), LIMITS)).isInstanceOf(ApiException.class);
        Path notZip = dir.resolve("bad.zip");
        Files.writeString(notZip, "这不是 zip");
        assertThatThrownBy(() -> ImportInspector.inspectZip(notZip, LIMITS))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("IMPORT_INVALID"));
        var tiny = new ImportInspector.Limits(50L << 20, 1, 200L << 20, 20L << 20);
        assertThatThrownBy(() -> ImportInspector.inspectZip(zip(m("a.html", "x", "b.html", "y")), tiny)).hasMessageContaining("文件太多");
        // 压缩炸弹：2 MB 的 0 压缩成几 KB
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("index.html"));
            z.write(new byte[2 << 20]);
            z.closeEntry();
        }
        Path bomb = dir.resolve("bomb.zip");
        Files.write(bomb, bos.toByteArray());
        assertThatThrownBy(() -> ImportInspector.inspectZip(bomb, LIMITS)).hasMessageContaining("压缩炸弹");
    }

    static java.util.List<String> codes(ImportInspector.Result r) { return r.warnings().stream().map(ProjectAnalyzer.Warning::code).toList(); }

    @Test
    void fullStackRepoDeploysOnlyFrontendAndExplainsBackendAndApi() throws Exception {
        var r = ImportInspector.inspectZip(zip(m(
                "repo/pom.xml", "<project/>",
                "repo/src/main/java/App.java", "class App {}",
                "repo/frontend/package.json", "{\"scripts\":{\"build\":\"vue-tsc && vite build\"},\"dependencies\":{\"vue\":\"3\",\"axios\":\"1\"},\"devDependencies\":{\"vite\":\"7\"}}",
                "repo/frontend/index.html", "<div id=app></div>",
                "repo/frontend/vite.config.ts", "export default { server: { proxy: { '/api': 'http://localhost:8080' } } }",
                "repo/frontend/src/api.ts", "import axios from 'axios'\nexport const list = () => axios.get('/api/users')",
                "repo/frontend/src/router.ts", "createRouter({ history: createWebHistory() })")), LIMITS);
        assertThat(r.deployable()).isTrue();
        assertThat(r.rootDir()).isEqualTo("frontend");
        assertThat(r.projectKind()).isEqualTo("vite");
        assertThat(r.framework()).isEqualTo("Vite + Vue");
        assertThat(codes(r)).contains("backend", "api", "typecheck", "history", "no-lock", "editable");
        assertThat(r.warnings().stream().filter(w -> w.code().equals("api")).findFirst().orElseThrow().detail()).contains("/api/", "proxy");
    }

    @Test
    void vueCliPublicHtmlIsNotACandidateAndDistIsOfferedAsPrebuilt() throws Exception {
        var r = ImportInspector.inspectZip(zip(m(
                "package.json", "{\"scripts\":{\"build\":\"vue-cli-service build\"},\"dependencies\":{\"vue\":\"2\"},\"devDependencies\":{\"@vue/cli-service\":\"4\"},\"engines\":{\"node\":\"14.x\"}}",
                "yarn.lock", "",
                "vue.config.js", "module.exports = { publicPath: '/admin/' }",
                "public/index.html", "<div id=app></div>",
                "src/main.js", "new Vue()",
                "dist/index.html", "<script src=/admin/js/app.js></script>",
                "dist/js/app.js", "1")), LIMITS);
        assertThat(r.framework()).isEqualTo("Vue CLI + Vue");
        assertThat(r.rootDir()).isEmpty();
        assertThat(r.candidates()).extracting(ProjectAnalyzer.Candidate::dir).containsExactly("", "dist");
        assertThat(r.candidates().get(1).prebuilt()).isTrue();
        assertThat(r.candidates().get(1).kind()).isEqualTo("static");
        assertThat(codes(r)).contains("prebuilt", "node", "yarn", "base");
    }

    @Test
    void privateRegistryAndLocalDepsAreRisks() throws Exception {
        var r = ImportInspector.inspectZip(zip(m(
                "package.json", "{\"scripts\":{\"build\":\"react-scripts build\"},\"dependencies\":{\"react\":\"18\",\"react-scripts\":\"5\",\"@corp/ui\":\"file:../ui\",\"node-sass\":\"7\"}}",
                ".npmrc", "@corp:registry=https://npm.corp.example/\n",
                "package-lock.json", "{}",
                "src/index.js", "1")), LIMITS);
        assertThat(r.framework()).isEqualTo("Create React App");
        assertThat(codes(r)).contains("registry", "local-deps", "native").doesNotContain("no-lock");
    }

    @Test
    void staticExportAndNestedStaticPages() throws Exception {
        assertThat(ImportInspector.inspectZip(zip(m("package.json", "{\"scripts\":{\"build\":\"next build\"},\"dependencies\":{\"next\":\"15\"}}",
                "next.config.mjs", "export default { output: 'export' }")), LIMITS).deployable()).isTrue();
        var s = ImportInspector.inspectZip(zip(m("index.html", "<h1>x</h1>", "about/index.html", "<p>a</p>", "app.js", "fetch('/api/list')")), LIMITS);
        assertThat(s.candidates()).hasSize(1);
        assertThat(codes(s)).contains("api");
        assertThat(ImportInspector.inspectZip(zip(m("pom.xml", "<project/>", "src/App.java", "x")), LIMITS).unsupportedReason()).contains("Java", "前端");
        assertThat(ImportInspector.inspectZip(zip(m("package.json", "{\"dependencies\":{\"express\":\"4\"}}")), LIMITS).framework()).isEqualTo("Node.js 服务端");
    }

    @Test
    void htmlChecks() {
        assertThat(ImportInspector.inspectHtml("<!doctype html><h1>hi</h1>".getBytes(), LIMITS).framework()).isEqualTo("HTML");
        assertThatThrownBy(() -> ImportInspector.inspectHtml("   ".getBytes(), LIMITS)).isInstanceOf(ApiException.class);
    }
}
