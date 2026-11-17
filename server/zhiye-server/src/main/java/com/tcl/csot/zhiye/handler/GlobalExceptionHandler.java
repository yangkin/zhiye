package com.tcl.csot.zhiye.handler;

import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.filter.SsoAuthFilter;
import com.tcl.csot.zhiye.service.ops.PlatformLogService;
import com.tcl.csot.zhiye.util.RequestPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final MessageSource messages;
    @Autowired(required = false)
    private PlatformLogService platformLogs;

    public GlobalExceptionHandler(MessageSource messages) { this.messages = messages; }

    /** 按请求语言取 i18n/core/messages*.properties 里的文字；找不到用 fallback */
    private String msg(String key, String fallback, Object... args) {
        return messages.getMessage(key, args, fallback, LocaleContextHolder.getLocale());
    }

    public static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code", code, "message", message));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> api(ApiException e, HttpServletRequest req) {
        String resolved = e.messageKey() == null ? e.getMessage() : msg(e.messageKey(), e.getMessage(), e.args());
        if (e.status().is5xxServerError() || (e.status().is4xxClientError() && e.status() != HttpStatus.NOT_FOUND)) {
            recordHttpError(req, e.status(), e.code(), resolved, e.status().is5xxServerError() ? e : null);
        }
        return body(e.status(), e.code(), resolved);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, IllegalArgumentException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, String>> bad(Exception e, HttpServletRequest req) {
        log.debug("请求参数解析失败", e);
        recordHttpError(req, HttpStatus.BAD_REQUEST, "VALIDATION", e.getMessage(), null);
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", msg("zhiye.error.validation", "请求格式不正确"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(Exception e, HttpServletRequest req) {
        recordHttpError(req, HttpStatus.BAD_REQUEST, "IMPORT_INVALID", "上传文件超过限制：" + e.getMessage(), null);
        return body(HttpStatus.BAD_REQUEST, "IMPORT_INVALID", msg("zhiye.error.upload-too-large", "文件太大：最多 50 MB，请删减后重新选择"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> method(Exception e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", msg("zhiye.error.method-not-allowed", "不支持的请求方法"));
    }

    @ExceptionHandler({NoResourceFoundException.class, org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<Map<String, String>> nores(Exception e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", msg("zhiye.error.api-not-found", "接口不存在"));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void gone(Exception e) { /* 客户端断开 */ }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e, HttpServletRequest req) {
        log.error("未处理的异常", e);
        recordHttpError(req, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", e.getMessage(), e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", msg("zhiye.error.internal", "服务暂时出错了，请稍后重试"));
    }

    private void recordHttpError(HttpServletRequest req, HttpStatus status, String code, String message, Throwable err) {
        if (platformLogs == null || req == null) return;
        String uri = req.getRequestURI();
        if (uri != null && uri.startsWith("/api/logs/")) return;
        Object wsAttr = req.getAttribute(RequestPrincipal.ATTR_WS);
        String ws = wsAttr instanceof String s ? s : null;
        Object userAttr = req.getAttribute(SsoAuthFilter.ATTR_USER);
        String userId = userAttr instanceof SsoUser u ? u.id() : null;
        String level = status.is5xxServerError() ? "ERROR" : "WARN";
        String summary = req.getMethod() + " " + uri + " → " + status.value() + " " + code + (message != null ? " · " + message : "");
        String detail = err != null ? PlatformLogService.stackTrace(err) : null;
        platformLogs.record("api_error", level, code, ws, userId, null, null, false, status.value(), null,
                summary, detail, Map.of("method", req.getMethod(), "uri", uri == null ? "" : uri));
    }
}
