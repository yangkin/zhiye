package com.tcl.csot.zhiye.service.llm;

/**
 * 额度对接点（技术方案 20.5.3）：应用内 AI、数据源、云函数调用前问一次「能不能调」，调用后报告用量。
 * 本期默认实现全部放行、只写日志；对接公司现有额度系统时只替换这个 Bean。
 */
public interface UsageHook {
    /** 不允许时抛 ApiException（例如 429 QUOTA_EXCEEDED） */
    void before(String appId, String userId, String kind);

    /** amount：token 数或次数 */
    void after(String appId, String userId, String kind, long amount);
}
