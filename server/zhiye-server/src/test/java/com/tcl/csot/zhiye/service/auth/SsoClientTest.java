package com.tcl.csot.zhiye.service.auth;

import com.tcl.csot.zhiye.client.SsoClient;
import com.tcl.csot.zhiye.config.AuthProperties;

import com.tcl.csot.zhiye.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SsoClientTest {
    @Test
    void parsesPlainUserObject() {
        var p = SsoClient.parse("{\"id\":746021448304578601,\"loginName\":\"zhangsan\",\"realName\":\"张三\",\"email\":\"zs@example.com\",\"tenantNum\":\"CSOT\"}");
        assertEquals("746021448304578601", p.ssoUserId());
        assertEquals("zhangsan", p.loginName());
        assertEquals("张三", p.realName());
        assertEquals("CSOT", p.tenantNum());
    }

    @Test
    void parsesWrappedResponse() {
        var p = SsoClient.parse("{\"code\":0,\"data\":{\"userId\":\"42\",\"username\":\"lisi\"}}");
        assertEquals("42", p.ssoUserId());
        assertEquals("lisi", p.loginName());
    }

    @Test
    void rejectsGarbageOrEmpty() {
        assertThrows(ApiException.class, () -> SsoClient.parse("not json"));
        assertThrows(ApiException.class, () -> SsoClient.parse("{\"code\":401,\"message\":\"unauthorized\"}"));
    }

    @Test
    void buildsLoginUrlLikeMetisScaffold() {
        var sso = new AuthProperties.Sso("http://testmetis.csot.tcl.com/", "/oauth/csot/login", "SSO-METIS",
                "http://127.0.0.1:8080/access_token", null, 10, "zhiye_session", 7, false);
        assertEquals("http://testmetis.csot.tcl.com/oauth/csot/login?rc=http://127.0.0.1:8080/access_token&client_id=SSO-METIS", sso.loginUrl());
        assertEquals("http://testmetis.csot.tcl.com/iam/tcl/v1/users/self", sso.resolvedUserInfoUrl());
    }
}
