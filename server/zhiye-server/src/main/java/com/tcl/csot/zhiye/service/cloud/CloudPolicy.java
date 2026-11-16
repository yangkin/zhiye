package com.tcl.csot.zhiye.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.http.HttpStatus;

import java.util.*;

/**
 * 每张表的权限预设与声明式规则（技术方案 20.4.3 / 20.4.4），来自应用源码里的 cloud/policies.json：
 * <pre>
 * { "tables": { "inspection": { "preset": "own_admin_read",
 *     "fields": { "qty": { "required": true, "min": 0 }, "code": { "unique": true }, "status": { "enum": ["待审","通过","驳回"] } },
 *     "transitions": { "status": [ { "from": "待审", "to": ["通过","驳回"], "by": "admin" } ] } } } }
 * </pre>
 * 由数据接口层执行，生成的代码和业务人员都不用写后端校验。
 */
public final class CloudPolicy {
    private CloudPolicy() {}

    /** 调用者：匿名时 userId 为 null；admin = 作者或管理员名单里的人；via = 由云函数代为执行时为 "fn:函数名"（不受权限预设限制） */
    public record Who(String userId, String loginName, String name, boolean admin, String via) {
        public static final Who ANON = new Who(null, null, null, false, null);
        public Who(String userId, String loginName, String name, boolean admin) { this(userId, loginName, name, admin, null); }
        public boolean anonymous() { return userId == null; }
        public boolean function() { return via != null; }
        public Who viaFunction(String fn) { return new Who(userId, loginName, name, admin, "fn:" + fn); }
        /** 写入 updated_by：直接调用记用户 id；云函数记 fn:名称:用户 id */
        public String actor() { return via == null ? userId : via + (userId == null ? "" : ":" + userId); }
        public Map<String, Object> view() {
            if (anonymous()) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", userId);
            m.put("loginName", loginName);
            m.put("name", name == null || name.isBlank() ? loginName : name);
            m.put("admin", admin);
            return m;
        }
    }

    public record Preset(String id, String label, String read, String write, boolean needsLogin) {}

    /** 读 / 写：all = 所有人，own = 本人，admin = 管理员，own_admin = 本人 + 管理员（审批），insert_all = 所有人只能新增 */
    public static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
    static {
        for (Preset p : List.of(
                new Preset("shared", "所有人可看可改", "all", "all", false),
                new Preset("admin_write", "所有人可看，管理员可改", "all", "admin", true),
                new Preset("own", "每人只看和改自己的", "own", "own", true),
                new Preset("own_admin_read", "自己提交，管理员看全部", "own_admin", "own_admin", true),
                new Preset("anon_submit", "免登录可提交，管理员查看", "admin", "insert_all", false),
                new Preset("function_write", "所有人可看，仅云函数可改", "all", "function", false)))
            PRESETS.put(p.id(), p);
    }
    public static final String DEFAULT_PRESET = "shared";

    public static Preset preset(JsonNode policy) {
        String id = policy == null ? null : policy.path("preset").asText(null);
        return PRESETS.getOrDefault(id == null ? DEFAULT_PRESET : id, PRESETS.get(DEFAULT_PRESET));
    }

    static ApiException denied(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "CLOUD_FORBIDDEN", message);
    }

    static ApiException loginRequired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "CLOUD_LOGIN_REQUIRED", "这个操作需要先登录");
    }

    /** 读：返回附加的 where 条件（null 表示不限制）；没有权限直接抛 */
    static String readFilter(Preset p, Who who) {
        if (who != null && who.function()) return null;
        return switch (p.read()) {
            case "all" -> null;
            case "admin" -> { requireLogin(who); if (!who.admin()) throw denied("只有管理员能查看这张表"); yield null; }
            case "own" -> { requireLogin(who); yield "created_by = ?"; }
            case "own_admin" -> { requireLogin(who); yield who.admin() ? null : "created_by = ?"; }
            default -> throw denied("没有读取权限");
        };
    }

    /** 新增 */
    static void checkInsert(Preset p, Who who) {
        if (who != null && who.function()) return;
        switch (p.write()) {
            case "all", "insert_all" -> { }
            case "admin" -> { requireLogin(who); if (!who.admin()) throw denied("只有管理员能新增"); }
            case "own", "own_admin" -> requireLogin(who);
            case "function" -> throw denied("只能由云函数写入，请通过应用内操作");
            default -> throw denied("没有写入权限");
        }
    }

    /** 修改 / 删除：返回附加的 where 条件（只能改自己的时是 created_by = ?） */
    static String writeFilter(Preset p, Who who) {
        if (who != null && who.function()) return null;
        return switch (p.write()) {
            case "all" -> null;
            case "insert_all" -> { requireLogin(who); if (!who.admin()) throw denied("提交后只有管理员能修改"); yield null; }
            case "admin" -> { requireLogin(who); if (!who.admin()) throw denied("只有管理员能修改"); yield null; }
            case "own" -> { requireLogin(who); yield "created_by = ?"; }
            case "own_admin" -> { requireLogin(who); yield who.admin() ? null : "created_by = ?"; }
            case "function" -> throw denied("只能由云函数修改或删除，请通过应用内操作");
            default -> throw denied("没有修改权限");
        };
    }

    private static void requireLogin(Who who) {
        if (who == null || who.anonymous()) throw loginRequired();
    }

    // ---------- policies.json ----------

    static final Set<String> FIELD_RULES = Set.of("required", "unique", "min", "max", "enum", "maxLength");

    /** 解析并校验；返回 表名 → 规则（preset 缺省为 shared）。tables 是 schema.sql 里声明的表 */
    static Map<String, ObjectNode> parse(String json, Map<String, ? extends Map<String, String>> tables) {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        JsonNode root;
        try { root = Json.M.readTree(json); } catch (Exception e) { throw ApiException.validation("cloud/policies.json 不是合法的 JSON：" + e.getMessage()); }
        JsonNode ts = root == null ? null : root.get("tables");
        if (ts == null || !ts.isObject()) throw ApiException.validation("cloud/policies.json 需要一个 tables 对象");
        var it = ts.fields();
        while (it.hasNext()) {
            var e = it.next();
            String table = e.getKey();
            if (!tables.containsKey(table)) throw ApiException.validation("cloud/policies.json 里的表 " + table + " 没有在 cloud/schema.sql 里声明");
            Map<String, String> cols = tables.get(table);
            JsonNode t = e.getValue();
            if (!t.isObject()) throw ApiException.validation("表 " + table + " 的规则要是一个对象");
            ObjectNode clean = Json.obj();
            String preset = t.path("preset").asText(DEFAULT_PRESET);
            if (!PRESETS.containsKey(preset)) throw ApiException.validation("表 " + table + " 的 preset「" + preset + "」不存在，可选：" + String.join(" / ", PRESETS.keySet()));
            clean.put("preset", preset);
            JsonNode fields = t.get("fields");
            if (fields != null) {
                if (!fields.isObject()) throw ApiException.validation("表 " + table + " 的 fields 要是一个对象");
                ObjectNode f = clean.putObject("fields");
                var fi = fields.fields();
                while (fi.hasNext()) {
                    var fe = fi.next();
                    if (!cols.containsKey(fe.getKey())) throw ApiException.validation("表 " + table + " 没有字段 " + fe.getKey());
                    if (!fe.getValue().isObject()) throw ApiException.validation("字段 " + fe.getKey() + " 的规则要是一个对象");
                    var ri = fe.getValue().fieldNames();
                    while (ri.hasNext()) {
                        String r = ri.next();
                        if (!FIELD_RULES.contains(r)) throw ApiException.validation("字段 " + fe.getKey() + " 的规则 " + r + " 不支持，可用：required / unique / min / max / enum / maxLength");
                    }
                    JsonNode en = fe.getValue().get("enum");
                    if (en != null && (!en.isArray() || en.isEmpty())) throw ApiException.validation("字段 " + fe.getKey() + " 的 enum 要是非空数组");
                    for (String num : List.of("min", "max", "maxLength"))
                        if (fe.getValue().has(num) && !fe.getValue().get(num).isNumber()) throw ApiException.validation("字段 " + fe.getKey() + " 的 " + num + " 要是数字");
                    f.set(fe.getKey(), fe.getValue());
                }
            }
            JsonNode tr = t.get("transitions");
            if (tr != null) {
                if (!tr.isObject()) throw ApiException.validation("表 " + table + " 的 transitions 要是一个对象");
                ObjectNode o = clean.putObject("transitions");
                var ti = tr.fields();
                while (ti.hasNext()) {
                    var te = ti.next();
                    if (!cols.containsKey(te.getKey())) throw ApiException.validation("表 " + table + " 没有字段 " + te.getKey());
                    if (!te.getValue().isArray() || te.getValue().isEmpty()) throw ApiException.validation("字段 " + te.getKey() + " 的状态流转要是非空数组");
                    for (JsonNode step : te.getValue()) {
                        if (!step.hasNonNull("from") || !step.path("to").isArray() || step.path("to").isEmpty())
                            throw ApiException.validation("状态流转每一项要有 from 和 to（数组）");
                        String by = step.path("by").asText("anyone");
                        if (!Set.of("anyone", "admin", "owner", "function").contains(by)) throw ApiException.validation("状态流转的 by 只能是 anyone / admin / owner / function");
                    }
                    o.set(te.getKey(), te.getValue());
                }
            }
            out.put(table, clean);
        }
        return out;
    }

    /** 字段规则：新增时检查必填；新增和修改都检查范围、枚举、长度（唯一性由 CloudService 查库） */
    static void checkFields(JsonNode policy, Map<String, Object> data, boolean insert) {
        JsonNode fields = policy == null ? null : policy.get("fields");
        if (fields == null || !fields.isObject()) return;
        var it = fields.fields();
        while (it.hasNext()) {
            var e = it.next();
            String col = e.getKey();
            JsonNode r = e.getValue();
            boolean present = data.containsKey(col);
            Object v = data.get(col);
            boolean blank = v == null || String.valueOf(v).isBlank();
            if (r.path("required").asBoolean(false) && (insert ? blank : present && blank))
                throw ApiException.validation("「" + col + "」必填");
            if (!present || blank) continue;
            String s = String.valueOf(v);
            if (r.has("enum")) {
                boolean ok = false;
                for (JsonNode x : r.get("enum")) if (x.asText().equals(s)) { ok = true; break; }
                if (!ok) throw ApiException.validation("「" + col + "」只能是：" + join(r.get("enum")));
            }
            if (r.has("maxLength") && s.length() > r.get("maxLength").asInt()) throw ApiException.validation("「" + col + "」最多 " + r.get("maxLength").asInt() + " 个字");
            if (r.has("min") || r.has("max")) {
                double d;
                try { d = Double.parseDouble(s); } catch (NumberFormatException ex) { throw ApiException.validation("「" + col + "」要是数字"); }
                if (r.has("min") && d < r.get("min").asDouble()) throw ApiException.validation("「" + col + "」不能小于 " + r.get("min").asText());
                if (r.has("max") && d > r.get("max").asDouble()) throw ApiException.validation("「" + col + "」不能大于 " + r.get("max").asText());
            }
        }
    }

    static List<String> uniqueFields(JsonNode policy) {
        List<String> out = new ArrayList<>();
        JsonNode fields = policy == null ? null : policy.get("fields");
        if (fields == null) return out;
        fields.fields().forEachRemaining(e -> { if (e.getValue().path("unique").asBoolean(false)) out.add(e.getKey()); });
        return out;
    }

    /** 状态流转：新值和旧值不同时，必须有一条 from=旧值、to 包含新值、by 满足的规则 */
    static void checkTransitions(JsonNode policy, Map<String, Object> before, Map<String, Object> data, Who who) {
        JsonNode tr = policy == null ? null : policy.get("transitions");
        if (tr == null || !tr.isObject()) return;
        var it = tr.fields();
        while (it.hasNext()) {
            var e = it.next();
            String col = e.getKey();
            if (!data.containsKey(col)) continue;
            String from = before.get(col) == null ? null : String.valueOf(before.get(col));
            String to = data.get(col) == null ? null : String.valueOf(data.get(col));
            if (Objects.equals(from, to)) continue;
            boolean allowedTarget = false, allowed = false;
            for (JsonNode step : e.getValue()) {
                if (!step.path("from").asText().equals(from)) continue;
                boolean hit = false;
                for (JsonNode x : step.get("to")) if (x.asText().equals(to)) hit = true;
                if (!hit) continue;
                allowedTarget = true;
                String by = step.path("by").asText("anyone");
                boolean owner = who != null && !who.anonymous() && Objects.equals(before.get("created_by"), who.userId());
                boolean viaFn = who != null && who.function();
                if (by.equals("anyone") || (by.equals("admin") && who != null && who.admin()) || (by.equals("owner") && owner) || (by.equals("function") && viaFn)) allowed = true;
            }
            if (!allowedTarget) throw ApiException.validation("「" + col + "」不能从「" + from + "」改成「" + to + "」");
            if (!allowed) throw denied("你没有权限把「" + col + "」从「" + from + "」改成「" + to + "」" + (e.getValue().toString().contains("\"function\"") ? "（要通过应用里的操作按钮完成）" : ""));
        }
    }

    /** 状态流转规则下，新增时状态只能是某条规则的起点 */
    static void checkInitialState(JsonNode policy, Map<String, Object> data) {
        JsonNode tr = policy == null ? null : policy.get("transitions");
        if (tr == null || !tr.isObject()) return;
        var it = tr.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (!data.containsKey(e.getKey()) || data.get(e.getKey()) == null) continue;
            String v = String.valueOf(data.get(e.getKey()));
            Set<String> starts = new LinkedHashSet<>(), targets = new HashSet<>();
            for (JsonNode step : e.getValue()) {
                starts.add(step.path("from").asText());
                for (JsonNode x : step.get("to")) targets.add(x.asText());
            }
            Set<String> initial = new LinkedHashSet<>(starts);
            initial.removeAll(targets);
            if (initial.isEmpty()) initial = starts;
            if (!initial.contains(v)) throw ApiException.validation("新记录的「" + e.getKey() + "」只能是：" + String.join("、", initial));
        }
    }

    private static String join(JsonNode arr) {
        List<String> l = new ArrayList<>();
        for (JsonNode x : arr) l.add(x.asText());
        return String.join("、", l);
    }

    /** 中文说明（控制台、发布面板用） */
    public static List<Map<String, Object>> presetViews() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Preset p : PRESETS.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("label", p.label());
            m.put("needsLogin", p.needsLogin());
            out.add(m);
        }
        return out;
    }
}
