package com.tcl.csot.zhiye.support;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 模拟 TFLE 的两步上传，并保存 ZIP 以验证发布后可以再次读取。 */
@RestController
@RequestMapping("/test-tfle/v1/{org}/files")
public class FakeSkillHubFileController {
    static final Map<String, byte[]> PACKAGES = new ConcurrentHashMap<>();
    public static volatile Map<String, String> lastUploadFields;

    static void reset() {
        PACKAGES.clear();
        lastUploadFields = null;
    }

    @PostMapping("/uuid")
    public Map<String, String> uuid() {
        return Map.of("content", "upload-1");
    }

    @PostMapping("/attachment/multipart")
    public String upload(@PathVariable String org, @RequestParam Map<String, String> params,
                         @RequestPart("file") MultipartFile file) throws IOException {
        lastUploadFields = params;
        String key = "skills/" + org + "/" + params.get("attachmentUUID") + "/" + file.getOriginalFilename();
        PACKAGES.put(key, file.getBytes());
        return key;
    }
}
