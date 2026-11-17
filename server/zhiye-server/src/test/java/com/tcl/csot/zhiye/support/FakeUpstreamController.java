package com.tcl.csot.zhiye.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用的「上游模型」：OpenAI 兼容，支持流式；计划请求返回固定 JSON。 */
@RestController
@Profile("test")
@RequestMapping("/test-upstream/v1")
public class FakeUpstreamController {
    public static final AtomicInteger CALLS = new AtomicInteger();
    public static final AtomicInteger TRUNCATED_CALLS = new AtomicInteger();
    public static volatile int failNext = 0;
    public static volatile JsonNode lastBody;
    /** 最近的请求体（并发跑测试时按内容找自己那一条，不依赖 lastBody） */
    public static final java.util.concurrent.ConcurrentLinkedDeque<JsonNode> BODIES = new java.util.concurrent.ConcurrentLinkedDeque<>();

    @PostMapping("/chat/completions")
    public void chat(@RequestBody JsonNode body, @RequestHeader("Authorization") String auth, HttpServletResponse res) throws Exception {
        CALLS.incrementAndGet();
        lastBody = body;
        BODIES.addLast(body);
        while (BODIES.size() > 100) BODIES.pollFirst();
        if (failNext > 0) {
            failNext--;
            res.setStatus(503);
            res.getWriter().write("{\"error\":{\"message\":\"busy\"}}");
            return;
        }
        String user = userText(body.path("messages").get(body.path("messages").size() - 1));
        if (user.contains("[truncated]") && user.contains("用户需求") && TRUNCATED_CALLS.getAndIncrement() == 0) {
            int maxTokens = body.path("max_tokens").asInt();
            var n = Json.obj();
            n.putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", "").put("reasoning_content", "思考尚未完成");
            ((com.fasterxml.jackson.databind.node.ObjectNode) n.path("choices").get(0)).put("finish_reason", "length");
            n.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", maxTokens);
            res.setContentType("application/json");
            res.getOutputStream().write(Json.write(n).getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (user.contains("[slow]") && user.contains("用户需求")) Thread.sleep(3000); // 让计划任务保持进行中一会儿
        // 带 [vue] 的需求：计划给出 Vue 形态
        String shape = user.contains("[vue]") && user.contains("用户需求") ? "\"shape\": \"vue\", " : "";
        // 带 [big] 的需求：计划按能力上限拆期，只写第一期
        String content = user.contains("[big]") && user.contains("用户需求")
                ? "{\"name\": \"设备管理平台\", \"scale\": \"phased\", \"planDoc\": \"# 设备管理平台\\n\\n## 目标\\n第一期做设备台账。\\n\\n## 分期计划\\n| 期次 | 内容 | 说明 |\\n| --- | --- | --- |\\n| 第一期 | 设备台账 | 本期 |\\n| 第二期 | 点检与保养 | 下一期 |\"}"
                : user.contains("用户需求")
                ? "好的：\n```json\n{" + shape + "\"name\": \"点击计数器\", \"planDoc\": \"# 点击计数器\\n\\n## 应用目标\\n显示点击次数，可以加一和重置。\\n\\n## 验收标准\\n- 按钮可用\"}\n```"
                : "好";
        // 问答的工具循环：带 [read_skill] 的问题先让「模型」调用 read_skill，拿到结果后把结果开头回显出来
        JsonNode last = body.path("messages").get(body.path("messages").size() - 1);
        boolean wantsSkill = body.toString().contains("[read_skill]") && body.path("tools").toString().contains("read_skill");
        if (body.path("stream").asBoolean(false) && (wantsSkill || "tool".equals(last.path("role").asText()))) {
            res.setContentType("text/event-stream");
            var out = res.getOutputStream();
            String chunk;
            if ("tool".equals(last.path("role").asText())) {
                String r = last.path("content").asText();
                chunk = Json.write(Json.obj().set("choices", Json.M.createArrayNode().add(Json.obj().put("index", 0)
                        .set("delta", Json.obj().put("content", "技能内容：" + r.substring(0, Math.min(r.length(), 40)))))));
            } else {
                chunk = "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"read_skill\",\"arguments\":\"{\\\"name\\\":\\\"charts-dashboard\\\"}\"}}]}}]}";
            }
            out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // 慢速流式（测试「停止回答」）：先给带 [[build]] 的第一段，停 1.5 秒再给第二段
        if (body.path("stream").asBoolean(false) && user.contains("[slowstream]")) {
            res.setContentType("text/event-stream");
            var out = res.getOutputStream();
            JsonNode[] parts = {Json.obj().put("reasoning_content", "想一想。"), Json.obj().put("content", "[[build:日常开发]] 第一段，"), null, Json.obj().put("content", "第二段。")};
            for (JsonNode d : parts) {
                if (d == null) { Thread.sleep(1500); continue; }
                String c = Json.write(Json.obj().set("choices", Json.M.createArrayNode().add(Json.obj().put("index", 0).set("delta", d))));
                out.write(("data: " + c + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // 会话里写文档：先给思考过程，再给带文件块的正文
        if (body.path("stream").asBoolean(false) && user.contains("[doc]")) {
            res.setContentType("text/event-stream");
            var out = res.getOutputStream();
            String answer = "这是一份文档，不需要做成应用，我直接写成文件。\n[[file:示例设计文档.md]]\n# 示例设计文档\n\n## 背景\n示例。\n[[/file]]\n### 文档说明\n- 示例";
            for (JsonNode d : new JsonNode[]{Json.obj().put("reasoning_content", "用户要的是一份文档，"), Json.obj().put("reasoning_content", "不是应用。"), Json.obj().put("content", answer)}) {
                String c = Json.write(Json.obj().set("choices", Json.M.createArrayNode().add(Json.obj().put("index", 0).set("delta", d))));
                out.write(("data: " + c + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (body.path("stream").asBoolean(false)) {
            res.setContentType("text/event-stream");
            var out = res.getOutputStream();
            String[] chunks = {
                    "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}]}",
                    "{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"};
            for (String c : chunks) { out.write(("data: " + c + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        var n = Json.obj();
        n.put("id", "x");
        n.putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", content);
        n.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 20);
        res.setContentType("application/json");
        res.getOutputStream().write(Json.write(n).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/models")
    public String models() { return "{\"data\":[{\"id\":\"kimi-for-coding\"}]}"; }

    /** 用户消息可能是多模态数组（带图片）：取其中的 text 部分 */
    private static String userText(JsonNode msg) {
        JsonNode c = msg.path("content");
        if (c.isTextual()) return c.asText();
        StringBuilder sb = new StringBuilder();
        if (c.isArray()) for (JsonNode p : c) if ("text".equals(p.path("type").asText())) sb.append(p.path("text").asText());
        return sb.toString();
    }
}
