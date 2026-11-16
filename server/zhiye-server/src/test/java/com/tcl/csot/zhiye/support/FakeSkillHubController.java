package com.tcl.csot.zhiye.support;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** 测试用的 SkillHub：记录最后一次发布请求。 */
@RestController
@Profile("test")
@RequestMapping("/test-skillhub/api/v1/skills")
public class FakeSkillHubController {
    public static volatile String lastAuth;
    public static volatile String lastMetadata;
    public static volatile byte[] lastPackage;

    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestHeader(value = "Authorization", required = false) String auth,
                                       @RequestPart("metadata") String metadata, @RequestPart("package") MultipartFile pkg) throws Exception {
        lastAuth = auth;
        lastMetadata = metadata;
        lastPackage = pkg.getBytes();
        return Map.of("skillId", "sk_123", "version", "1.0.0", "url", "https://skillhub.example/skills/sk_123");
    }
}
