package com.tcl.csot.zhiye.util;

import java.util.regex.Pattern;

/**
 * 运维日志与平台事件脱敏工具：
 * 在存储及对外读取/下载/导出路径统一遮罩凭证、Bearer Token、API Key、Cookie 及数据库连接串中的明文密码。
 */
public final class LogRedactor {
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{16,}");
    private static final Pattern HEADER_SECRETS = Pattern.compile(
            "(?i)((?:x-api-key|x-zhiye-admin-token|set-cookie|cookie)\\s*[:=]\\s*)[^\\r\\n;]{6,}");
    private static final Pattern SK_KEY = Pattern.compile(
            "\\bsk-[A-Za-z0-9_-]{16,}\\b");
    private static final Pattern DB_URI_CREDS = Pattern.compile(
            "\\b(postgresql|postgres|mysql|redis|amqps?|mongodb)://([^:@/\\s]+):([^@/\\s]+)@");
    private static final Pattern KV_SECRETS = Pattern.compile(
            "(?i)((?:api[_-]?key|secret[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|client_secret)\\s*[:=]\\s*[\"']?)[^\\s\"',;}{)]{6,}");

    private LogRedactor() {}

    public static String redact(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String out = AUTH_HEADER.matcher(raw).replaceAll("$1***");
        out = BEARER_TOKEN.matcher(out).replaceAll("$1***");
        out = HEADER_SECRETS.matcher(out).replaceAll("$1***");
        out = SK_KEY.matcher(out).replaceAll("sk-***");
        out = DB_URI_CREDS.matcher(out).replaceAll("$1://$2:***@");
        out = KV_SECRETS.matcher(out).replaceAll("$1***");
        return out;
    }
}
