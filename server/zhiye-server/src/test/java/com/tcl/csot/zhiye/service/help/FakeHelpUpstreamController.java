package com.tcl.csot.zhiye.service.help;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * 测试用的小织上游模型（OpenAI 兼容流式）。按最后一条用户消息里的标记决定行为：
 * [slow] 慢速输出很多块；[fail] 返回 500；其它：先推理块再输出「你好，我是小织」。
 */
@RestController
@Profile("test")
@RequestMapping("/test-help-upstream/v1")
public class FakeHelpUpstreamController {
    public static volatile JsonNode lastBody;
    public static volatile boolean slowFinished;

    @PostMapping("/chat/completions")
    public void chat(@RequestBody JsonNode body, HttpServletResponse res) throws Exception {
        lastBody = body;
        JsonNode last = body.path("messages").get(body.path("messages").size() - 1).path("content");
        String user = last.isArray() ? last.toString() : last.asText();
        if (user.contains("[fail]")) {
            res.setStatus(500);
            res.getWriter().write("{\"error\":{\"message\":\"boom\"}}");
            return;
        }
        res.setContentType("text/event-stream");
        var out = res.getOutputStream();
        send(out, "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"想一想\"}}]}");
        if (user.contains("[slow]")) {
            slowFinished = false;
            try {
                for (int i = 0; i < 100; i++) {
                    send(out, "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"块" + i + " \"}}]}");
                    Thread.sleep(100);
                }
                slowFinished = true;
            } catch (Exception e) {
                return; // 下游断开
            }
        } else {
            send(out, "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好，\"}}]}");
            send(out, "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"我是小织\"}}]}");
        }
        send(out, "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
        send(out, "{\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void send(java.io.OutputStream out, String json) throws Exception {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
