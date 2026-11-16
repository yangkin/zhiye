package com.tcl.csot.zhiye.util;

import com.tcl.csot.zhiye.exception.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 从请求属性读取匿名工作空间身份。 */
@Component
public class RequestPrincipal implements CurrentPrincipal {
    public static final String ATTR_WS = "zhiye.workspaceId";
    public static final String ATTR_TOKEN = "zhiye.token";

    private HttpServletRequest req() {
        var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) throw new IllegalStateException("不在请求上下文中");
        return attrs.getRequest();
    }

    @Override
    public String workspaceId() {
        String ws = workspaceIdOrNull();
        if (ws == null) throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录");
        return ws;
    }

    @Override
    public String workspaceIdOrNull() {
        Object ws = req().getAttribute(ATTR_WS);
        return ws == null || ((String) ws).isEmpty() ? null : (String) ws;
    }

    @Override
    public String rawToken() { return (String) req().getAttribute(ATTR_TOKEN); }
}
