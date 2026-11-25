package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.runtime.LiveWorkspaceService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** 持久化活体开发环境接口：状态、启动/唤醒、休眠、重置、受控安全终端执行、实时 SSE 日志流。 */
@RestController
@Profile("api")
@RequestMapping("/api/apps/{appId}/live-workspace")
public class LiveWorkspaceController {
    private final LiveWorkspaceService live;
    private final CurrentPrincipal principal;

    public LiveWorkspaceController(LiveWorkspaceService live, CurrentPrincipal principal) {
        this.live = live;
        this.principal = principal;
    }

    @GetMapping
    public Map<String, Object> status(@PathVariable String appId) {
        return live.status(principal.workspaceId(), appId);
    }

    @PostMapping("/start")
    public Map<String, Object> start(@PathVariable String appId) throws Exception {
        return live.startOrWake(principal.workspaceId(), appId);
    }

    @PostMapping("/sleep")
    public Map<String, Object> sleep(@PathVariable String appId) {
        return live.sleep(principal.workspaceId(), appId);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@PathVariable String appId) throws Exception {
        return live.reset(principal.workspaceId(), appId);
    }

    @PostMapping("/exec")
    public Map<String, Object> exec(@PathVariable String appId, @RequestBody JsonNode body) throws Exception {
        return live.execCommand(principal.workspaceId(), appId, body == null ? "" : body.path("command").asText(""));
    }

    @GetMapping(value = "/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String appId) {
        return live.streamLogs(principal.workspaceId(), appId);
    }
}
