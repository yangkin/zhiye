package com.tcl.csot.zhiye.entity;

/** 统一认证返回、并保存在织页的用户信息（不保存网关令牌）。 */
public record SsoUser(String id, String ssoUserId, String loginName, String realName, String email, String tenantNum) {
    /** 返回给前端的精简信息 */
    public java.util.Map<String, Object> view() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("loginName", loginName);
        m.put("realName", realName == null || realName.isBlank() ? loginName : realName);
        m.put("email", email);
        return m;
    }
}
