package com.tcl.csot.zhiye.client;

import com.tcl.csot.zhiye.config.FivestarProperties;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.llm.LlmRoute;
import com.tcl.csot.zhiye.util.ServerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 调用上游模型服务，对 429/5xx/网络错误在收到首字节前退避重试。
 * 上游由 {@link LlmRoute} 决定：本地模式是 zhiye.llm 的统一上游，五星模式是 llm-gateway 上员工自己的路由。
 * 额度用完的 429 不重试（重试只会白白等待），原样交给调用方提示用户。
 */
@Component
public class UpstreamClient {
    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final ZhiyeProperties props;
    private final FivestarProperties fivestar;
    private final ServerPort port;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.common.RuntimeMetricsService metrics;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public UpstreamClient(ZhiyeProperties props, FivestarProperties fivestar, ServerPort port) {
        this.props = props;
        this.fivestar = fivestar;
        this.port = port;
    }

    public String baseUrl() {
        return props.llm().upstreamBaseUrl().replace("{self}", port.selfBase()).replaceAll("/+$", "");
    }

    private String apiKey() {
        String k = System.getenv(props.llm().apiKeyEnv());
        if (k == null || k.isBlank()) k = System.getProperty(props.llm().apiKeyEnv());
        return k == null ? "" : k.trim();
    }

    /** 本地统一上游的路由 */
    public LlmRoute localRoute() {
        return new LlmRoute(baseUrl(), apiKey(), props.llm().model(), props.llm().provider(), Map.of(), null, props.llm().model(), true, true);
    }

    /** 模型是否可用：五星模式下密钥按人下发，这里只看本地密钥 */
    public boolean hasKey() { return fivestar.enabled() || !apiKey().isEmpty(); }

    /** 发送请求（本地上游）；返回的响应体是流，调用方负责关闭 */
    public HttpResponse<InputStream> send(String method, String path, byte[] body) throws IOException, InterruptedException {
        return send(localRoute(), method, path, body);
    }

    /** 应用内 AI 用：较短的超时、较少的重试 */
    public HttpResponse<InputStream> send(String method, String path, byte[] body, Duration timeout, int retries) throws IOException, InterruptedException {
        return send(localRoute(), method, path, body, timeout, retries);
    }

    public HttpResponse<InputStream> send(LlmRoute route, String method, String path, byte[] body) throws IOException, InterruptedException {
        return send(route, method, path, body, Duration.ofMinutes(10), props.llm().maxRetries());
    }

    public HttpResponse<InputStream> send(LlmRoute route, String method, String path, byte[] body, Duration timeout, int retries)
            throws IOException, InterruptedException {
        int max = Math.max(0, retries);
        long backoff = props.llm().retryBackoffMillis();
        IOException lastIo = null;
        long startedMs = System.currentTimeMillis();
        int retried = 0;
        for (int attempt = 0; attempt <= max; attempt++) {
            if (attempt > 0) {
                retried++;
                Thread.sleep(backoff * (1L << (attempt - 1)));
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(route.baseUrl() + path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + route.apiKey())
                    .header("User-Agent", "zhiye-gateway/0.1");
            route.headers().forEach(b::header);
            if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            else b.method(method, HttpRequest.BodyPublishers.noBody());
            try {
                HttpResponse<InputStream> res = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                int s = res.statusCode();
                if ((s == 429 || s >= 500) && attempt < max) {
                    byte[] all;
                    try (InputStream in = res.body()) { all = in.readAllBytes(); }
                    if (s == 429 && "PROVIDER_QUOTA".equals(KimiClient.codeFor(s, new String(all, StandardCharsets.UTF_8)))) {
                        if (metrics != null) metrics.recordLlmCall(System.currentTimeMillis() - startedMs, false, true, retried);
                        return new Buffered(res, all);
                    }
                    log.warn("上游模型返回 {}，第 {} 次重试", s, attempt + 1);
                    continue;
                }
                if (metrics != null) metrics.recordLlmCall(System.currentTimeMillis() - startedMs, s < 400, s == 429, retried);
                return res;
            } catch (IOException e) {
                lastIo = e;
                log.warn("上游模型连接失败：{}，第 {} 次重试", e.getMessage(), attempt + 1);
            }
        }
        if (metrics != null) metrics.recordLlmCall(System.currentTimeMillis() - startedMs, false, false, retried);
        throw lastIo != null ? lastIo : new IOException("上游模型不可用");
    }

    /** 已经读出响应体的响应（额度用完的 429） */
    private record Buffered(HttpResponse<InputStream> res, byte[] bytes) implements HttpResponse<InputStream> {
        public int statusCode() { return res.statusCode(); }
        public HttpRequest request() { return res.request(); }
        public Optional<HttpResponse<InputStream>> previousResponse() { return res.previousResponse(); }
        public HttpHeaders headers() { return res.headers(); }
        public InputStream body() { return new ByteArrayInputStream(bytes); }
        public Optional<SSLSession> sslSession() { return res.sslSession(); }
        public URI uri() { return res.uri(); }
        public HttpClient.Version version() { return res.version(); }
    }
}
