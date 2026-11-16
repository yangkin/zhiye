package com.tcl.csot.zhiye.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 共享的 ObjectMapper 小工具（JSONB 读写用）。 */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    private Json() {}

    public static String write(Object o) {
        try { return M.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static JsonNode read(String s) {
        if (s == null) return null;
        try { return M.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static <T> T read(String s, Class<T> t) {
        if (s == null) return null;
        try { return M.readValue(s, t); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static <T> T read(String s, TypeReference<T> t) {
        if (s == null) return null;
        try { return M.readValue(s, t); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static <T> T convert(Object o, Class<T> t) { return M.convertValue(o, t); }

    public static ObjectNode obj() { return M.createObjectNode(); }

    public static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
