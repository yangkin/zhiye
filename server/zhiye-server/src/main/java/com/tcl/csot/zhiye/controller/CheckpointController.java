package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.task.CheckpointService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 工作台检查点时间旅行接口：列出检查点（含独立 QA 评分与报告）、一键回退到指定检查点。 */
@RestController
@Profile("api")
@RequestMapping("/api/apps/{appId}/checkpoints")
public class CheckpointController {
    private final CheckpointService checkpoints;
    private final CurrentPrincipal principal;

    public CheckpointController(CheckpointService checkpoints, CurrentPrincipal principal) {
        this.checkpoints = checkpoints;
        this.principal = principal;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String appId) {
        return checkpoints.list(principal.workspaceId(), appId);
    }

    @PostMapping("/{checkpointId}/restore")
    public Map<String, Object> restore(@PathVariable String appId, @PathVariable String checkpointId) throws Exception {
        return checkpoints.restore(principal.workspaceId(), appId, checkpointId);
    }
}
