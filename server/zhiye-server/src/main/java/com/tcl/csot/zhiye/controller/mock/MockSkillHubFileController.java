package com.tcl.csot.zhiye.controller.mock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "zhiye.skillhub", name = "mock", havingValue = "true")
@RequestMapping("/mock/tfile/v1/{org}/files")
public class MockSkillHubFileController {
    private static final Map<String, byte[]> PACKAGES = new ConcurrentHashMap<>();

    @PostMapping("/uuid")
    public Map<String, String> uuid() {
        return Map.of("content", UUID.randomUUID().toString());
    }

    @PostMapping("/attachment/multipart")
    public String upload(@PathVariable String org, @RequestParam String attachmentUUID,
                         @RequestPart("file") MultipartFile file) throws IOException {
        String key = "skills/" + org + "/" + attachmentUUID + "/" + file.getOriginalFilename();
        PACKAGES.put(key, file.getBytes());
        return key;
    }

    static byte[] packageBytes(String key) {
        return key == null ? null : PACKAGES.get(key);
    }
}
