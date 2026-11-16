package com.tcl.csot.zhiye.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 前端带来的平台登录令牌（Authorization: Bearer ...）。只用来以用户身份调用五星AI岛的用户接口，不保存、不打印。
 * 织页自己的登录态仍是 httpOnly 会话 cookie。
 */
public final class PlatformToken {
    private PlatformToken() {}

    public static String of(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String t = h.substring(7).trim();
        return t.isEmpty() || t.length() > 4096 ? null : t;
    }
}
