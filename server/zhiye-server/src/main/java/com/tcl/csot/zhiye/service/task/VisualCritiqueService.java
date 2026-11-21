package com.tcl.csot.zhiye.service.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.service.llm.LlmRoute;
import com.tcl.csot.zhiye.service.llm.LlmRouter;
import com.tcl.csot.zhiye.service.llm.UsageService;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 校验阶段的强制视觉评审门禁：当冒烟测试采集到真实桌面截图时，用多模态模型快速复核一次，
 * 专查严重视觉缺陷（按钮/文字重叠、明显遮挡、排版坍塌、大面积空白或未替换占位符）。
 * 上游不可用或超时时默认放行（fail-open），不阻塞正常交付。
 */
@Service
public class VisualCritiqueService {
    private static final Logger log = LoggerFactory.getLogger(VisualCritiqueService.class);
    private static final String SYSTEM = """
            你是资深 UI/UX 视觉质检专家。你将看到网页应用构建后的真实宽屏（1920×1080）首屏截图，请只检查**明显且严重**的视觉或结构缺陷：
            1. 控件重叠、文字互相遮挡、按钮或表格被裁切得无法阅读；
            2. 页面主内容区大面积空白、出现原始代码/[[file:]]/[[next]] 等泄露标记、明显报错提示；
            3. 对比度严重不足（例如浅灰字配白底几乎看不清）或导航/卡片排版完全坍塌；
            4. 业务后台、管理台账或数据看板被锁死在窄居中容器里（例如左侧导航栏没有贴浏览器左边缘、左右两侧同时出现大块空带留白，像是套了 max-w-[1440px] mx-auto）。
            规则：
            - 如果整体正常、可用、美观（轻微审美偏好不算严重缺陷），只输出：PASS
            - 只有存在上述严重缺陷时，用一行中文输出：FIX: 具体问题描述与修改建议（不超过 180 字）
            """;

    private final UpstreamClient upstream;
    private final LlmRouter router;
    private final UsageService usage;

    public VisualCritiqueService(UpstreamClient upstream, LlmRouter router, UsageService usage) {
        this.upstream = upstream;
        this.router = router;
        this.usage = usage;
    }

    public Optional<String> review(String taskId, AppRow app, String screenshotBase64) {
        if (screenshotBase64 == null || screenshotBase64.isBlank() || !upstream.hasKey()) return Optional.empty();
        try {
            LlmRoute route = router.forTask(taskId);
            ObjectNode req = Json.obj();
            req.put("model", route.model());
            req.put("max_tokens", 300);
            ArrayNode msgs = req.putArray("messages");
            msgs.addObject().put("role", "system").put("content", SYSTEM);
            ObjectNode user = msgs.addObject().put("role", "user");
            ArrayNode parts = user.putArray("content");
            String desc = app == null ? "" : ("应用名称：" + app.name() + "\n用户需求：" + truncate(app.prompt(), 300));
            parts.addObject().put("type", "text").put("text", desc + "\n请检查这张首屏截图是否存在严重视觉/布局问题：");
            parts.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", "data:image/jpeg;base64," + screenshotBase64);

            var res = upstream.send(route, "POST", "/chat/completions", Json.write(req).getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(20), 0);
            String raw;
            try (InputStream in = res.body()) { raw = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (res.statusCode() >= 400) {
                log.debug("视觉评审跳过（HTTP {}）", res.statusCode());
                return Optional.empty();
            }
            JsonNode root = Json.M.readTree(raw);
            JsonNode u = UsageService.findUsage(root);
            if (u != null) usage.record(taskId, route.provider(), route.model(), u);
            String reply = root.path("choices").path(0).path("message").path("content").asText("");
            return parseCritique(reply);
        } catch (Exception e) {
            log.debug("视觉评审异常放行 task={}：{}", taskId, e.toString());
            return Optional.empty();
        }
    }

    public static Optional<String> parseCritique(String reply) {
        if (reply == null) return Optional.empty();
        String s = reply.trim();
        if (s.isEmpty() || s.toUpperCase().startsWith("PASS")) return Optional.empty();
        if (s.toUpperCase().startsWith("FIX:")) {
            String issue = s.substring(4).trim();
            return issue.isEmpty() ? Optional.empty() : Optional.of(truncate(issue, 240));
        }
        if (s.toUpperCase().startsWith("FIX：")) {
            String issue = s.substring(4).trim();
            return issue.isEmpty() ? Optional.empty() : Optional.of(truncate(issue, 240));
        }
        return Optional.empty();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
