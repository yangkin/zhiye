package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.dev.FaultState;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** POST /api/dev/faults {"llm":"none|error","build":"none|error"} —— 仅开发环境。 */
@RestController
@Profile("api")
@ConditionalOnProperty(name = "zhiye.dev.faults-enabled", havingValue = "true")
@RequestMapping("/api/dev/faults")
public class DevFaultController {
    private final FaultState faults;

    public DevFaultController(FaultState faults) { this.faults = faults; }

    @PostMapping
    public Map<String, String> set(@RequestBody Map<String, String> body) {
        String llm = body.get("llm"), build = body.get("build");
        if (llm != null && !llm.matches("none|error")) throw new IllegalArgumentException();
        if (build != null && !build.matches("none|error")) throw new IllegalArgumentException();
        faults.set(llm, build);
        return get();
    }

    @GetMapping
    public Map<String, String> get() { return Map.of("llm", faults.llm(), "build", faults.build()); }
}
