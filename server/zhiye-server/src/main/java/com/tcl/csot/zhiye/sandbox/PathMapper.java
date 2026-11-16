package com.tcl.csot.zhiye.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 在工作进程路径和沙箱内路径之间互相改写。
 * <p>
 * runner 协议里有几个字段是绝对路径（init.agentDir、init.sessionFile、import.archivePath、回复里的 sessionFile），
 * 工作进程看到的是 {runtime}/workspaces/{id}、{runtime}/agents/{id}，沙箱里是 /workspace、/agent。
 * 只改写<b>整个字符串值</b>以某个前缀开头的情况（不改写 prompt 文本中间出现的路径）。
 */
public final class PathMapper {
    private final String workerWorkspace;
    private final String workerAgent;
    private final String sandboxWorkspace;
    private final String sandboxAgent;

    public PathMapper(String workerWorkspace, String workerAgent, String sandboxWorkspace, String sandboxAgent) {
        this.workerWorkspace = trim(workerWorkspace);
        this.workerAgent = trim(workerAgent);
        this.sandboxWorkspace = trim(sandboxWorkspace);
        this.sandboxAgent = trim(sandboxAgent);
    }

    private static String trim(String s) { return s.endsWith("/") && s.length() > 1 ? s.substring(0, s.length() - 1) : s; }

    /** 旧名，Kubernetes Provider 还在用。 */
    public String toPod(String s) { return toSandbox(s); }

    public String toSandbox(String s) {
        String r = swap(s, workerAgent, sandboxAgent);
        return r != null ? r : (r = swap(s, workerWorkspace, sandboxWorkspace)) != null ? r : s;
    }

    public String toWorker(String s) {
        String r = swap(s, sandboxAgent, workerAgent);
        return r != null ? r : (r = swap(s, sandboxWorkspace, workerWorkspace)) != null ? r : s;
    }

    private static String swap(String s, String from, String to) {
        if (s.equals(from)) return to;
        if (s.startsWith(from + "/")) return to + s.substring(from.length());
        return null;
    }

    /** 原地改写 JSON 里所有字符串值（只改写整串以前缀开头的）。 */
    public void rewrite(JsonNode n, boolean toSandbox) {
        if (n instanceof ObjectNode o) {
            for (Iterator<Map.Entry<String, JsonNode>> it = o.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v.isTextual()) {
                    String m = toSandbox ? toSandbox(v.asText()) : toWorker(v.asText());
                    if (!m.equals(v.asText())) e.setValue(TextNode.valueOf(m));
                } else rewrite(v, toSandbox);
            }
        } else if (n instanceof ArrayNode a) {
            for (int i = 0; i < a.size(); i++) {
                JsonNode v = a.get(i);
                if (v.isTextual()) {
                    String m = toSandbox ? toSandbox(v.asText()) : toWorker(v.asText());
                    if (!m.equals(v.asText())) a.set(i, TextNode.valueOf(m));
                } else rewrite(v, toSandbox);
            }
        }
    }
}