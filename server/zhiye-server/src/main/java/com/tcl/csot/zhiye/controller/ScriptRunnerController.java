package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.runtime.ScriptRunnerService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 无页面 Python / Node.js 脚本在线运行控制台 API。 */
@RestController
@Profile("api")
@RequestMapping("/api/apps/{appId}/script")
public class ScriptRunnerController {
    private final ScriptRunnerService runner;
    private final CurrentPrincipal principal;

    public ScriptRunnerController(ScriptRunnerService runner, CurrentPrincipal principal) {
        this.runner = runner;
        this.principal = principal;
    }

    private String ws() { return principal.workspaceId(); }

    @GetMapping("/files")
    public Map<String, Object> files(@PathVariable String appId,
                                     @RequestParam(required = false) String versionId) throws Exception {
        return runner.inspect(ws(), appId, versionId);
    }

    @PostMapping("/run")
    public Map<String, Object> run(@PathVariable String appId,
                                   @RequestBody(required = false) JsonNode body) throws Exception {
        String versionId = body == null ? null : body.path("versionId").asText(null);
        String entryFile = body == null ? "" : body.path("entryFile").asText("");
        String args = body == null ? "" : body.path("args").asText("");
        String stdin = body == null ? "" : body.path("stdin").asText("");
        Integer timeoutSec = body != null && body.hasNonNull("timeoutSeconds") ? body.path("timeoutSeconds").asInt() : null;
        Map<String, String> env = new LinkedHashMap<>();
        if (body != null && body.has("env") && body.get("env").isObject()) {
            body.get("env").fields().forEachRemaining(e -> {
                if (e.getValue() != null && !e.getValue().isNull()) env.put(e.getKey(), e.getValue().asText(""));
            });
        }
        return runner.run(ws(), appId, versionId, entryFile, args, stdin, env, timeoutSec);
    }
}
