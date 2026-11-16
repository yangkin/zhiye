package com.tcl.csot.zhiye.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务错误：响应体 { code, message }。
 * message 可以直接给中文，也可以给 i18n/core/messages*.properties 里的 messageKey（{@link #ofKey}），由 GlobalExceptionHandler 按请求语言解析。
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String messageKey;
    private final Object[] args;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    private ApiException(HttpStatus status, String code, String message, String messageKey, Object[] args) {
        super(message);
        this.status = status;
        this.code = code;
        this.messageKey = messageKey;
        this.args = args;
    }

    /** 用 messageKey 构造；defaultMessage 是找不到 key 时的中文兜底 */
    public static ApiException ofKey(HttpStatus status, String code, String messageKey, String defaultMessage, Object... args) {
        return new ApiException(status, code, defaultMessage, messageKey, args);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String messageKey() { return messageKey; }
    public Object[] args() { return args; }

    public static ApiException notFound(String what) { return ofKey(HttpStatus.NOT_FOUND, "NOT_FOUND", "zhiye.error.not-found", what + "不存在", what); }
    public static ApiException validation(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", msg); }
    public static ApiException conflict(String code, String msg) { return new ApiException(HttpStatus.CONFLICT, code, msg); }
    public static ApiException busy() { return ofKey(HttpStatus.CONFLICT, "APP_BUSY", "zhiye.error.app-busy", "应用有正在进行的任务，请等待完成或先停止它"); }
    public static ApiException draftPending() { return ofKey(HttpStatus.CONFLICT, "DRAFT_PENDING", "zhiye.error.draft-pending", "有未保存的可视化编辑，请先保存或放弃"); }
}
