package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.app.Catalog;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 无页面 Python / Node.js (JS/MJS) 脚本导入、意图识别与沙箱在线运行完整端到端测试。 */
class ScriptSupportAndRunnerTest extends IntegrationTest {

    @Test
    void catalogRecognizesHeadlessPythonAndNodeScripts() {
        assertThat(Catalog.looksLikeScript("帮我写一个 Node.js 脚本批量统计 CSV 日志")).isTrue();
        assertThat(Catalog.looksLikeScript("写一个 js 脚本把 JSON 转为表格统计")).isTrue();
        assertThat(Catalog.looksLikeScript("写一个无页面的 python 数据清洗脚本")).isTrue();
        assertThat(Catalog.explicitlyNoPageScript("写一个无页面的 Node.js 脚本处理日志")).isTrue();
        assertThat(Catalog.explicitlyNoPageScript("做一个带图表的生产看板页面")).isFalse();
        assertThat(Catalog.looksLikeScript("做一个 Python 后台管理看板页面")).isFalse();
    }

    @Test
    void singlePythonScriptImportsAndRunsWithArgsStdinAndOutputFile() {
        Client c = client();
        String pyCode = """
                import argparse, json, sys
                p = argparse.ArgumentParser()
                p.add_argument("--tag", default="default")
                args = p.parse_args()
                lines = [l.strip() for l in sys.stdin.read().splitlines() if l.strip()]
                total = sum(int(x) for x in lines)
                res = {"tag": args.tag, "count": len(lines), "total": total}
                with open("summary.json", "w", encoding="utf-8") as f:
                    json.dump(res, f, ensure_ascii=False)
                print(f"TAG={args.tag} COUNT={len(lines)} TOTAL={total}")
                """;
        JsonNode m = c.ok(c.upload("/api/imports/upload", "calc_yield.py", "text/x-python",
                pyCode.getBytes(StandardCharsets.UTF_8), Map.of()));
        assertThat(m.path("deployable").asBoolean()).isTrue();
        assertThat(m.path("format").asText()).isEqualTo("script");
        assertThat(m.path("projectKind").asText()).isEqualTo("script");
        assertThat(m.path("framework").asText()).isEqualTo("Python 脚本");

        JsonNode created = c.ok(c.post("/api/imports", Map.of(
                "uploadId", m.path("uploadId").asText(),
                "name", "良率统计Python脚本",
                "type", "日常开发",
                "rootDir", "",
                "projectKind", "script")));
        String appId = created.path("app").path("id").asText();
        assertThat(c.waitTask(created.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode inspected = c.ok(c.get("/api/apps/" + appId + "/script/files"));
        assertThat(inspected.path("defaultEntry").asText()).isEqualTo("calc_yield.py");

        JsonNode run = c.ok(c.post("/api/apps/" + appId + "/script/run", Map.of(
                "entryFile", "calc_yield.py",
                "args", "--tag \"Fab T6\"",
                "stdin", "10\n25\n65\n")));
        assertThat(run.path("runtime").asText()).isEqualTo("python");
        assertThat(run.path("exitCode").asInt()).isEqualTo(0);
        assertThat(run.path("timedOut").asBoolean()).isFalse();
        assertThat(run.path("stdout").asText()).contains("TAG=Fab T6 COUNT=3 TOTAL=100");
        assertThat(run.path("outputFiles").toString()).contains("summary.json").contains("\\\"total\\\": 100");

        // 历史记录已持久化
        JsonNode after = c.ok(c.get("/api/apps/" + appId + "/script/files"));
        assertThat(after.path("history").size()).isGreaterThanOrEqualTo(1);
        assertThat(after.path("history").get(0).path("exitCode").asInt()).isEqualTo(0);

        // 非法路径逃逸被拦截
        assertThat(c.post("/api/apps/" + appId + "/script/run", Map.of("entryFile", "../evil.py")).status()).isEqualTo(400);
    }

    @Test
    void singleNodeJsScriptImportsRunsAndCapturesNonZeroExit() {
        Client c = client();
        String jsCode = """
                import fs from 'node:fs';
                const args = process.argv.slice(2);
                if (args.includes('--fail')) {
                  console.error('INVALID_BATCH_INPUT');
                  process.exit(2);
                }
                const input = fs.readFileSync(0, 'utf8').trim();
                const items = input ? input.split(',').map((s) => Number(s.trim())) : [];
                const sum = items.reduce((a, b) => a + b, 0);
                console.log(JSON.stringify({ mode: args[0] || 'sum', sum, n: items.length }));
                """;
        JsonNode m = c.ok(c.upload("/api/imports/upload", "batch_calc.mjs", "text/javascript",
                jsCode.getBytes(StandardCharsets.UTF_8), Map.of()));
        assertThat(m.path("deployable").asBoolean()).isTrue();
        assertThat(m.path("projectKind").asText()).isEqualTo("script");
        assertThat(m.path("framework").asText()).contains("Node.js");

        JsonNode created = c.ok(c.post("/api/imports", Map.of(
                "uploadId", m.path("uploadId").asText(),
                "name", "批处理Node脚本",
                "type", "日常开发",
                "rootDir", "",
                "projectKind", "script")));
        String appId = created.path("app").path("id").asText();
        assertThat(c.waitTask(created.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode okRun = c.ok(c.post("/api/apps/" + appId + "/script/run", Map.of(
                "entryFile", "batch_calc.mjs",
                "args", "aggregate",
                "stdin", "12, 18, 30")));
        assertThat(okRun.path("runtime").asText()).isEqualTo("node");
        assertThat(okRun.path("exitCode").asInt()).isEqualTo(0);
        assertThat(okRun.path("stdout").asText()).contains("\"sum\":60").contains("\"mode\":\"aggregate\"");

        JsonNode errRun = c.ok(c.post("/api/apps/" + appId + "/script/run", Map.of(
                "entryFile", "batch_calc.mjs",
                "args", "--fail")));
        assertThat(errRun.path("exitCode").asInt()).isEqualTo(2);
        assertThat(errRun.path("stderr").asText()).contains("INVALID_BATCH_INPUT");
    }

    @Test
    void multiFileHeadlessPythonZipImportsAsScriptAndTimeoutKillsProcess() throws Exception {
        Client c = client();
        byte[] zipBytes = ImportFlowTest.zip(Map.of(
                "etl-pack/etl.py", "import csv, sys, time\n"
                        + "if '--sleep' in sys.argv:\n"
                        + "    time.sleep(10)\n"
                        + "with open(sys.argv[1], encoding='utf-8') as f:\n"
                        + "    rows = list(csv.DictReader(f))\n"
                        + "print('ROWS=' + str(len(rows)) + ',FIRST=' + rows[0]['lot'])\n",
                "etl-pack/examples/lots.csv", "lot,yield\nLOT-A,99.2\nLOT-B,98.7\n",
                "etl-pack/README.md", "# ETL Script"
        ));
        JsonNode m = c.ok(c.upload("/api/imports/upload", "etl-pack.zip", "application/zip", zipBytes, Map.of()));
        assertThat(m.path("deployable").asBoolean()).isTrue();
        assertThat(m.path("projectKind").asText()).isEqualTo("script");

        JsonNode created = c.ok(c.post("/api/imports", Map.of(
                "uploadId", m.path("uploadId").asText(),
                "name", "多文件ETL脚本包",
                "rootDir", "",
                "projectKind", "script")));
        String appId = created.path("app").path("id").asText();
        assertThat(c.waitTask(created.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode run = c.ok(c.post("/api/apps/" + appId + "/script/run", Map.of(
                "entryFile", "etl.py",
                "args", "examples/lots.csv")));
        assertThat(run.path("exitCode").asInt()).isEqualTo(0);
        assertThat(run.path("stdout").asText()).contains("ROWS=2,FIRST=LOT-A");

        JsonNode timedOut = c.ok(c.post("/api/apps/" + appId + "/script/run", Map.of(
                "entryFile", "etl.py",
                "args", "--sleep",
                "timeoutSeconds", 1)));
        assertThat(timedOut.path("timedOut").asBoolean()).isTrue();
        assertThat(timedOut.path("stderr").asText()).contains("已自动终止");
    }

    @Test
    void headlessHttpPythonAndNodeSingleFilesOfferServiceAndScriptCandidates() {
        Client c = client();
        String pyHttp = "from http.server import HTTPServer, BaseHTTPRequestHandler\nHTTPServer(('0.0.0.0', 8080), BaseHTTPRequestHandler).serve_forever()\n";
        JsonNode m1 = c.ok(c.upload("/api/imports/upload", "api_server.py", "text/x-python",
                pyHttp.getBytes(StandardCharsets.UTF_8), Map.of()));
        assertThat(m1.path("projectKind").asText()).isEqualTo("service");
        assertThat(m1.path("candidates").findValuesAsText("kind")).containsExactly("service", "script");

        String nodeHttp = "import http from 'node:http'; http.createServer((req,res)=>res.end('ok')).listen(process.env.PORT||8080);\n";
        JsonNode m2 = c.ok(c.upload("/api/imports/upload", "webhook.mjs", "text/javascript",
                nodeHttp.getBytes(StandardCharsets.UTF_8), Map.of()));
        assertThat(m2.path("projectKind").asText()).isEqualTo("service");
        assertThat(m2.path("candidates").findValuesAsText("kind")).containsExactly("service", "script");
    }
}
