package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.workspace.WorkspaceMemoryService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 个人工作空间长期记忆 API（/api/workspace/memories）。 */
@RestController
@Profile("api")
@RequestMapping("/api/workspace/memories")
public class WorkspaceMemoryController {
    private final WorkspaceMemoryService memories;
    private final CurrentPrincipal principal;

    public WorkspaceMemoryController(WorkspaceMemoryService memories, CurrentPrincipal principal) {
        this.memories = memories;
        this.principal = principal;
    }

    @GetMapping
    public Map<String, Object> list() {
        String ws = principal.workspaceId();
        return Map.of(
                "items", memories.list(ws),
                "maxItems", WorkspaceMemoryService.MAX_ITEMS_PER_WORKSPACE,
                "autoLearnEnabled", memories.isAutoLearnEnabled(ws)
        );
    }

    @PatchMapping("/settings")
    public Map<String, Object> updateSettings(@RequestBody JsonNode body) {
        boolean enabled = body != null && body.path("autoLearnEnabled").asBoolean(true);
        boolean saved = memories.setAutoLearnEnabled(principal.workspaceId(), enabled);
        return Map.of("autoLearnEnabled", saved);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody JsonNode body) {
        return memories.add(principal.workspaceId(), body);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody JsonNode body) {
        return memories.update(principal.workspaceId(), id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        memories.delete(principal.workspaceId(), id);
        return Map.of("ok", true);
    }

    @DeleteMapping
    public Map<String, Object> clearAll() {
        int deleted = memories.clearAll(principal.workspaceId());
        return Map.of("ok", true, "deleted", deleted);
    }
}
