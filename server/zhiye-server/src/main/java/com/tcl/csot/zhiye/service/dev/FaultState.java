package com.tcl.csot.zhiye.service.dev;

import org.springframework.stereotype.Component;

/** 开发用故障注入开关（只在 zhiye.dev.faults-enabled=true 时可以通过接口修改）。 */
@Component
public class FaultState {
    private volatile String llm = "none";
    private volatile String build = "none";

    public boolean llmError() { return "error".equals(llm); }
    public boolean buildError() { return "error".equals(build); }
    public String llm() { return llm; }
    public String build() { return build; }

    public void set(String llm, String build) {
        if (llm != null) this.llm = llm;
        if (build != null) this.build = build;
    }
}
