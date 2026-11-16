package com.tcl.csot.zhiye.service.version;

import com.tcl.csot.zhiye.storage.ObjectStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 「下载页面」：把发布版 index.html 引用的本地脚本和样式内联成一个 HTML 文件。 */
@Component
public class PageExporter {
    private static final Pattern SCRIPT = Pattern.compile("<script([^>]*?)\\ssrc=[\"']([^\"']+)[\"']([^>]*)>\\s*</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("<link([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL = Pattern.compile("rel=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private final ObjectStore store;

    public PageExporter(ObjectStore store) { this.store = store; }

    public String inline(String prefix) throws Exception {
        String html = read(prefix, "index.html").orElseThrow(() -> new IllegalStateException("发布版缺少 index.html"));
        StringBuilder out = new StringBuilder();
        Matcher m = SCRIPT.matcher(html);
        while (m.find()) {
            String attrs = (m.group(1) + " " + m.group(3)).trim();
            Optional<String> js = isLocal(m.group(2)) ? read(prefix, clean(m.group(2))) : Optional.empty();
            String rep = js.map(s -> "<script" + (attrs.isEmpty() ? "" : " " + attrs.replaceAll("\\s*crossorigin(=\"[^\"]*\")?", "")) + ">"
                    + s.replaceAll("(?i)</script", "<\\\\/script") + "</script>").orElse(m.group(0));
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        html = out.toString();
        out = new StringBuilder();
        m = LINK.matcher(html);
        while (m.find()) {
            String attrs = m.group(1);
            Matcher rel = REL.matcher(attrs), href = HREF.matcher(attrs);
            String rep = m.group(0);
            if (rel.find() && href.find() && isLocal(href.group(1))) {
                String r = rel.group(1).toLowerCase();
                if (r.contains("stylesheet")) {
                    Optional<String> css = read(prefix, clean(href.group(1)));
                    if (css.isPresent()) rep = "<style>" + css.get() + "</style>";
                } else if (r.contains("modulepreload")) rep = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isLocal(String url) { return !url.matches("(?i)^(https?:)?//.*|^data:.*"); }

    private static String clean(String url) {
        String u = url.split("[?#]")[0];
        while (u.startsWith("./")) u = u.substring(2);
        while (u.startsWith("/")) u = u.substring(1);
        return u;
    }

    private Optional<String> read(String prefix, String path) throws Exception {
        if (path.contains("..")) return Optional.empty();
        return store.getBytes(prefix + "/" + path).map(b -> new String(b, StandardCharsets.UTF_8));
    }
}
