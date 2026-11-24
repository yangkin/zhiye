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
                .satisfies(r -> { assertThat(r.framework()).contains("Next.js"); assertThat(r.deployable()).isTrue(); assertThat(r.projectKind()).isEqualTo("service"); });
        assertThat(ImportInspector.inspectZip(zip(m("package.json", "{\"dependencies\":{\"nuxt\":\"3\"}}")), LIMITS).framework()).isEqualTo("Nuxt.js");
        var vite = ImportInspector.inspectZip(zip(m("v/package.json", "{\"dependencies\":{\"react\":\"19\"},\"devDependencies\":{\"vite\":\"7\"}}", "v/index.html", "<div id=root>")), LIMITS);
        assertThat(vite.framework()).isEqualTo("Vite + React");
        assertThat(vite.projectKind()).isEqualTo("vite");
        assertThat(ImportInspector.inspectZip(zip(m("package.json", "{\"dependencies\":{\"vue\":\"3\",\"vite\":\"7\"}}")), LIMITS).framework()).isEqualTo("Vite + Vue");
        assertThat(ImportInspector.inspectZip(zip(m("package.json", "{\"dependencies\":{\"react\":\"19\"}}")), LIMITS).framework()).isEqualTo("React");
        var py = ImportInspector.inspectZip(zip(m("app.py", "print(1)", "requirements.txt", "flask")), LIMITS);
        assertThat(py.framework()).contains("Python");
        assertThat(py.deployable()).isTrue();
        assertThat(py.projectKind()).isEqualTo("service");
        var go = ImportInspector.inspectZip(zip(m("go.mod", "module x")), LIMITS);
        assertThat(go.framework()).isEqualTo("Go");
        assertThat(go.deployable()).isTrue();
        assertThat(go.projectKind()).isEqualTo("service");
        var java = ImportInspector.inspectZip(zip(m("pom.xml", "<project/>")), LIMITS);
        assertThat(java.framework()).contains("Java");
        assertThat(java.deployable()).isTrue();
        assertThat(java.projectKind()).isEqualTo("service");
        assertThat(ImportInspector.inspectZip(zip(m("composer.json", "{}")), LIMITS).deployable()).isFalse();
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
    void fullStackRepoOffersMonolithServiceAndFrontendCandidate() throws Exception {
        var r = ImportInspector.inspectZip(zip(m(
                "repo/pom.xml", "<project/>",
                "repo/src/main/java/App.java", "class App {}",
                "repo/frontend/package.json", "{\"scripts\":{\"build\":\"vue-tsc && vite build\"},\"dependencies\":{\"vue\":\"3\",\"axios\":\"1\"},\"devDependencies\":{\"vite\":\"7\"}}",
                "repo/frontend/index.html", "<div id=app></div>",
                "repo/frontend/vite.config.ts", "export default { server: { proxy: { '/api': 'http://localhost:8080' } } }",
                "repo/frontend/src/api.ts", "import axios from 'axios'\nexport const list = () => axios.get('/api/users')",
                "repo/frontend/src/router.ts", "createRouter({ history: createWebHistory() })")), LIMITS);
        assertThat(r.deployable()).isTrue();
        assertThat(r.rootDir()).isEmpty();
        assertThat(r.projectKind()).isEqualTo("service");
        assertThat(r.framework()).contains("Java").contains("Vue");
        assertThat(r.candidates()).extracting(ProjectAnalyzer.Candidate::dir).containsExactly("", "frontend");
        assertThat(r.candidates().get(1).kind()).isEqualTo("vite");
        assertThat(codes(r)).contains("service-runtime", "fullstack", "scale-to-zero");
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
        assertThat(ImportInspector.inspectZip(zip(m("pom.xml", "<project/>", "src/App.java", "x")), LIMITS).deployable()).isTrue();
        var exp = ImportInspector.inspectZip(zip(m("package.json", "{\"dependencies\":{\"express\":\"4\"}}")), LIMITS);
        assertThat(exp.framework()).contains("Express");
        assertThat(exp.deployable()).isTrue();
        assertThat(exp.projectKind()).isEqualTo("service");
    }

    @Test
    void htmlChecks() {
        assertThat(ImportInspector.inspectHtml("<!doctype html><h1>hi</h1>".getBytes(), LIMITS).framework()).isEqualTo("HTML");
        assertThatThrownBy(() -> ImportInspector.inspectHtml("   ".getBytes(), LIMITS)).isInstanceOf(ApiException.class);
    }

    @Test
    void messyNestedZipAndJunkDirsAutoUnwrapped() throws Exception {
        // 业务用户常见的三层套壳 + .venv / __pycache__ / .idea / __MACOSX 混乱压缩包
        var r = ImportInspector.inspectZip(zip(m(
                "外包交付-v2/proj-final/my-app/requirements.txt", "fastapi\nuvicorn\n",
                "外包交付-v2/proj-final/my-app/main.py", "import os\nport = int(os.environ.get('PORT', 8000))\n",
                "外包交付-v2/proj-final/my-app/.venv/lib/site.py", "pass",
                "外包交付-v2/proj-final/my-app/__pycache__/main.cpython-312.pyc", "bin",
                "外包交付-v2/proj-final/my-app/.idea/workspace.xml", "<xml/>",
                "__MACOSX/._README", "mac"
        )), LIMITS);
        assertThat(r.deployable()).isTrue();
        assertThat(r.projectKind()).isEqualTo("service");
        assertThat(r.rootDir()).isEmpty();
        assertThat(r.files()).extracting(ImportInspector.FileInfo::path)
                .containsExactlyInAnyOrder("requirements.txt", "main.py");
    }

    @Test
    void preflightRiskScannersCatchSecretsMiddlewareLocalhostAndPort() throws Exception {
        // 混合多项高风险项的真实反面用例：.env 凭证 + Redis/MySQL + 前端硬编码 localhost + 写死端口
        var r = ImportInspector.inspectZip(zip(m(
                "bad-app/requirements.txt", "flask\nredis==5.0.0\npymysql==1.1.0\n",
                "bad-app/.env", "SECRET_KEY=prod-secret-value\n",
                "bad-app/app.py", "from flask import Flask\napp = Flask(__name__)\napp.run(host='127.0.0.1', port=5000)\n",
                "bad-app/frontend/src/api.ts", "export const API = 'http://localhost:8000/api'\n"
        )), LIMITS);
        assertThat(r.deployable()).isTrue();
        assertThat(r.projectKind()).isEqualTo("service");
        assertThat(r.warnings()).extracting(ProjectAnalyzer.Warning::code)
                .contains("secret-file", "middleware-deps", "hardcoded-localhost", "port-env");
    }

    @Test
    void unsupportedLanguagesAndBrokenManifestsGiveClearBoundaryMessage() throws Exception {
        var rust = ImportInspector.inspectZip(zip(m(
                "Cargo.toml", "[package]\nname = \"api\"\n",
                "src/main.rs", "fn main() {}"
        )), LIMITS);
        assertThat(rust.deployable()).isFalse();
        assertThat(rust.unsupportedReason()).contains("Rust").contains("Dockerfile");

        var brokenPkg = ImportInspector.inspectZip(zip(m(
                "package.json", "{ broken json !!"
        )), LIMITS);
        assertThat(brokenPkg.deployable()).isFalse();
    }
}
