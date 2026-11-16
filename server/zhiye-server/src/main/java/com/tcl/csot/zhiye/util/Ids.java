package com.tcl.csot.zhiye.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class Ids {
    private static final SecureRandom RND = new SecureRandom();
    private static final char[] ALNUM = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private Ids() {}

    public static String random(int len) {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = ALNUM[RND.nextInt(ALNUM.length)];
        return new String(c);
    }

    /** 形如 app_k3j...（前缀 + 下划线 + 16 位） */
    public static String newId(String prefix) { return prefix + "_" + random(16); }

    /** 站点子域名：字母开头的 10 位小写字母数字 */
    public static String slug() { return "s" + random(9); }

    /** 256 位随机令牌（hex） */
    public static String token() {
        byte[] b = new byte[32];
        RND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
