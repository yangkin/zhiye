package com.tcl.csot.zhiye.exception;

/** Java 侧模型调用的异常；code 与 runner 侧的错误码保持一致（MODEL_ERROR / PROVIDER_QUOTA / BUDGET_EXCEEDED）。 */
public class LlmException extends Exception {
    private final String code;

    public LlmException(String message) { this("MODEL_ERROR", message); }

    public LlmException(String message, Throwable cause) { super(message, cause); this.code = "MODEL_ERROR"; }

    public LlmException(String code, String message) { super(message); this.code = code == null || code.isBlank() ? "MODEL_ERROR" : code; }

    public String code() { return code; }
}
