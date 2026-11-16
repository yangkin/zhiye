package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.task.TaskQueueService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
public class TaskQueueController {
    private final TaskQueueService queued;
    private final CurrentPrincipal principal;

    public TaskQueueController(TaskQueueService queued, CurrentPrincipal principal) {
        this.queued = queued;
        this.principal = principal;
    }

    @GetMapping("/api/apps/{appId}/queued")
    public List<Map<String, Object>> list(@PathVariable String appId) {
        return queued.list(principal.workspaceId(), appId);
    }

    @PostMapping("/api/tasks/{taskId}/queued")
    public Map<String, Object> enqueue(@PathVariable String taskId, @RequestBody(required = false) JsonNode body) {
        return queued.enqueue(principal.workspaceId(), taskId, body == null ? "" : body.path("content").asText(""),
                body != null && body.path("enableCloud").asBoolean(false));
    }

    @DeleteMapping("/api/queued/{id}")
    public ResponseEntity<Void> cancel(@PathVariable String id) {
        queued.cancel(principal.workspaceId(), id);
        return ResponseEntity.noContent().build();
    }
}
