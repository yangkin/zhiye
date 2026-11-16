package com.tcl.csot.zhiye.service.llm;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 额度用完时给用户看的话：五星模式下说明是五星AI岛的个人额度，并带上重置时间（llm-gateway 错误体里有的话）。 */
public final class QuotaText {
    private static final Pattern RESET = Pattern.compile("\"(?:reset_at|token_next_reset_at|resetAt)\"\\s*:\\s*\"([^\"]+)\"");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private QuotaText() {}

    /** 错误体里的重置时间（reset_at / token_next_reset_at / resetAt），格式化成北京时间；没有返回 null。错误体可能夹在 runner 的说明文字里，所以按正则找 */
    public static String resetAt(String body) {
        if (body == null) return null;
        Matcher m = RESET.matcher(body);
        if (!m.find()) return null;
        try { return FMT.format(Instant.parse(m.group(1))); } catch (Exception e) { return null; }
    }

    public static String exhausted(boolean fivestar, String body) {
        if (!fivestar) return "模型服务配额已用完，请稍后重试或联系管理员";
        String at = resetAt(body);
        return "你在五星AI岛的本周模型额度已用完" + (at == null ? "" : "，" + at + " 重置") + "。";
    }
}
