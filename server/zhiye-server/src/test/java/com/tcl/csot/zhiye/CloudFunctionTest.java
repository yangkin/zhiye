package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 云函数（20.7 验收）：「通过」由函数在事务里执行（写审批记录 + 改单据状态）；直接调数据接口改状态被拒绝；
 * 函数报错有记录；超时被停止；发布后同源可调；函数没有文件 / 环境变量 / 外网权限。
 */
class CloudFunctionTest extends IntegrationTest {
    static final String SCHEMA = "create table requests (title text, status text);\ncreate table approvals (request_id bigint, decision text);";
    static final String POLICIES = """
            { "tables": {
              "requests": { "preset": "shared",
                "fields": { "status": { "enum": ["待审", "通过", "驳回"] } },
                "transitions": { "status": [ { "from": "待审", "to": ["通过", "驳回"], "by": "function" } ] } },
              "approvals": { "preset": "function_write" } } }
            """;
    static final String APPROVE = """
            import type { Handler } from './zhiye-fn.d.ts'
            const handler: Handler = async (args, ctx) => {
              const id = Number(args.id)
              const r = await ctx.db.get('requests', id)
              if (!r) throw new Error('单据不存在')
              ctx.log('审批', id)
              const results = await ctx.db.transaction([
                { op: 'insert', table: 'approvals', row: { request_id: id, decision: String(args.decision ?? '通过') } },
                { op: 'update', table: 'requests', id, row: { status: String(args.decision ?? '通过') } },
              ])
              return { status: results[1].status, by: ctx.user ? ctx.user.name : null, updatedBy: results[1].updated_by }
            }
            export default handler
            """;
    static final String BOOM = "export default async () => { const x: any = null; return x.field }\n";
    static final String SLOW = "export default async () => { while (true) {} }\n";
    static final String ESCAPE = """
            export default async () => {
              const out: string[] = []
              for (const f of [() => Deno.readTextFileSync('/etc/hosts'), () => Deno.env.get('HOME'), () => fetch('https://example.com')]) {
                try { await f(); out.push('ok') } catch (e) { out.push((e as Error).name) }
              }
              return out
            }
            """;

    JsonNode previewCfg(Client c, String id) {
        String html = c.get(local(c.ok(c.get("/api/apps/" + id)).path("previewUrl").asText())).body();
        Matcher m = Pattern.compile("window.__ZHIYE_CLOUD__=(\\{.*?\\});</script>").matcher(html);
        assertThat(m.find()).isTrue();
        return Json.read(m.group(1));
    }

    @Test
    void approvalFunctionTransactionsLogsAndLimits() {
        Client c = client();
        String id = c.createApp("做一个领料审批").path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        String v1 = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();

        // 编译不过的函数：保存时校验失败
        JsonNode broken = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(
                Map.of("path", "functions/broken.ts", "content", "export default async (a => {")))));
        JsonNode bt = c.waitTask(broken.path("id").asText());
        assertThat(bt.path("status").asText()).isEqualTo("FAILED");
        assertThat(bt.toString()).contains("broken.ts");

        JsonNode save = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(
                Map.of("path", "cloud/schema.sql", "content", SCHEMA), Map.of("path", "cloud/policies.json", "content", POLICIES),
                Map.of("path", "functions/approve.ts", "content", APPROVE), Map.of("path", "functions/boom.ts", "content", BOOM),
                Map.of("path", "functions/slow.ts", "content", SLOW), Map.of("path", "functions/escape.ts", "content", ESCAPE)))));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        c.ok(c.post("/api/apps/" + id + "/cloud/enable", Map.of()));
        Map<String, String> auth = Map.of("Authorization", "Bearer " + previewCfg(c, id).path("token").asText());
        String db = "/cloud/" + id + "/db/";

        JsonNode req = c.ok(c.send("POST", db + "requests", Map.of("title", "领 10 个螺丝", "status", "待审"), auth));
        String rid = req.path("id").asText();
        // 直接调数据接口改成「通过」：拒绝（状态流转只允许云函数）
        var direct = c.send("PATCH", db + "requests/" + rid, Map.of("status", "通过"), auth);
        assertThat(direct.status()).isEqualTo(403);
        assertThat(direct.body()).contains("应用里的操作");
        // function_write：前端不能直接写入，只能通过云函数写入
        var directApproval = c.send("POST", db + "approvals", Map.of("request_id", rid, "decision", "通过"), auth);
        assertThat(directApproval.status()).isEqualTo(403);
        assertThat(directApproval.body()).contains("云函数");

        // 函数执行：事务里写审批记录 + 改状态，updated_by 记为 fn:approve
        JsonNode r = c.ok(c.send("POST", "/cloud/" + id + "/fn/approve", Map.of("args", Map.of("id", rid)), auth));
        assertThat(r.path("result").path("status").asText()).isEqualTo("通过");
        assertThat(r.path("result").path("updatedBy").asText()).startsWith("fn:approve");
        assertThat(r.path("logs").toString()).contains("审批");
        JsonNode approvals = c.ok(c.send("GET", db + "approvals", null, auth));
        assertThat(approvals.path("total").asInt()).isEqualTo(1);
        String aid = approvals.path("rows").get(0).path("id").asText();
        assertThat(c.send("PATCH", db + "approvals/" + aid, Map.of("decision", "驳回"), auth).status()).isEqualTo(403);
        assertThat(c.send("DELETE", db + "approvals/" + aid, null, auth).status()).isEqualTo(403);

        // 事务回滚：已经是「通过」再改「驳回」不允许，第一步插入的审批记录也不保留
        var again = c.send("POST", "/cloud/" + id + "/fn/approve", Map.of("args", Map.of("id", rid, "decision", "驳回")), auth);
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body()).contains("事务第 2 步");
        assertThat(c.ok(c.send("GET", db + "approvals", null, auth)).path("total").asInt()).isEqualTo(1);

        // 报错、超时、不存在
        var boom = c.send("POST", "/cloud/" + id + "/fn/boom", Map.of(), auth);
        assertThat(boom.status()).isEqualTo(400);
        assertThat(boom.code()).isEqualTo("FN_ERROR");
        assertThat(boom.body()).contains("云函数 boom 出错");
        assertThat(c.send("POST", "/cloud/" + id + "/fn/slow", Map.of(), auth).code()).isEqualTo("FN_TIMEOUT");
        assertThat(c.send("POST", "/cloud/" + id + "/fn/nope", Map.of(), auth).code()).isEqualTo("FN_NOT_FOUND");
        // 没有文件、环境变量、外网权限
        assertThat(c.ok(c.send("POST", "/cloud/" + id + "/fn/escape", Map.of(), auth)).path("result").toString()).doesNotContain("ok");
        // 伪造令牌不能直接用内部接口
        assertThat(c.send("POST", "/fnapi/db/list", Map.of("table", "requests"), Map.of("Authorization", "Bearer zf.e30.x")).status()).isEqualTo(401);

        // 控制台：函数列表与报错记录
        JsonNode ov = c.ok(c.get("/api/apps/" + id + "/cloud/console/functions"));
        assertThat(ov.path("available").asBoolean()).isTrue();
        assertThat(ov.path("functions").toString()).contains("approve").contains("boom").doesNotContain("zhiye-fn");
        assertThat(ov.path("logs").toString()).contains("boom").contains("超过 10 秒");

        // 发布后同源调用线上版本的函数
        c.ok(c.send("PATCH", "/api/apps/" + id, Map.of("name", "领料审批"), Map.of()));
        JsonNode pub = c.ok(c.post("/api/apps/" + id + "/publish", Map.of()));
        String host = pub.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        Map<String, String> h = Map.of("Host", host, "X-Zhiye-Request", "1");
        JsonNode req2 = c.ok(c.send("POST", "/__zhiye/db/requests", Map.of("title", "领手套", "status", "待审"), h));
        JsonNode sr = c.ok(c.send("POST", "/__zhiye/fn/approve", Map.of("args", Map.of("id", req2.path("id").asLong())), h));
        assertThat(sr.path("result").path("status").asText()).isEqualTo("通过");
        assertThat(sr.has("logs")).isFalse();
        assertThat(c.send("POST", "/__zhiye/fn/approve", Map.of("args", Map.of("id", 1)), Map.of("Host", host)).status()).isEqualTo(400);
    }
}
