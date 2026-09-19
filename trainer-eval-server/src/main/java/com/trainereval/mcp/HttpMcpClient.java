package com.trainereval.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streamable HTTP 传输的 MCP 客户端：把 JSON-RPC 请求 POST 到 MCP 端点。
 *
 * <h3>为什么还要支持 HTTP</h3>
 * stdio 要求 MCP server 和本应用在同一台机器上（要起子进程）。团队共享一个 MCP 服务
 * （比如内部的制度检索、工单系统）只能用 HTTP。
 *
 * <h3>两个容易踩的点</h3>
 * <ol>
 *   <li><b>响应可能是 SSE</b>。Streamable HTTP 允许服务端用 {@code text/event-stream}
 *       流式返回，所以 {@code Accept} 必须同时声明两种类型，解析时两条路都要走。</li>
 *   <li><b>会话头要带回去</b>。服务端在 {@code initialize} 的响应头里给
 *       {@code Mcp-Session-Id}，后续请求必须回带，否则会被当成新会话（表现为"工具列表是空的"）。</li>
 * </ol>
 */
@Slf4j
public class HttpMcpClient extends AbstractMcpClient {

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final HttpClient httpClient;
    private volatile String sessionId;

    public HttpMcpClient(String serverName, McpProperties.Server config, ObjectMapper objectMapper,
                         Duration defaultTimeout) {
        super(serverName, config, objectMapper, defaultTimeout);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    protected void open() throws Exception {
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            throw new McpException(serverName, "http 传输必须配置 url");
        }
        // 只做 URL 合法性校验，不做探测：真正的连通性由 initialize 那一次请求证明
        URI.create(config.getUrl());
    }

    @Override
    protected JsonNode exchange(ObjectNode request, Duration timeout) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.getUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                // 两条路都要：服务端可能返回单个 JSON，也可能返回 SSE 流
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)));

        if (config.getHeaders() != null) {
            config.getHeaders().forEach(builder::header);
        }
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        response.headers().firstValue(SESSION_HEADER).ifPresent(id -> {
            if (!id.isBlank()) {
                sessionId = id;
            }
        });

        if (response.statusCode() == 404 || response.statusCode() == 405) {
            throw new McpException(serverName, "端点不存在或方法不允许（HTTP " + response.statusCode()
                    + "），请确认 url 指向的是 MCP Streamable HTTP 端点而不是 SSE 端点");
        }
        if (response.statusCode() >= 400) {
            throw new McpException(serverName, "HTTP " + response.statusCode() + "："
                    + abbreviate(response.body()));
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String body = response.body();
        if (contentType.contains("text/event-stream")) {
            return findInSse(body, request.path("id").asText());
        }
        if (body == null || body.isBlank()) {
            return null;
        }
        return objectMapper.readTree(body);
    }

    /**
     * 从 SSE 文本里找出与请求 id 匹配的那条 JSON-RPC 响应。
     * SSE 的一行格式是 {@code data: {json}}，其间还可能夹杂 {@code event:}/{@code id:}/空行。
     */
    private JsonNode findInSse(String body, String expectedId) throws Exception {
        for (String rawLine : body.split("\r?\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonNode node;
            try {
                node = objectMapper.readTree(payload);
            } catch (Exception ignored) {
                continue;
            }
            if (node.hasNonNull("id") && expectedId.equals(node.path("id").asText())) {
                return node;
            }
        }
        throw new McpException(serverName, "SSE 响应里没有找到 id=" + expectedId + " 的结果");
    }

    @Override
    protected void shutdown() {
        // HTTP 是无状态的；给服务端一个礼貌的会话终止请求，失败也无所谓
        if (sessionId == null || config.getUrl() == null) {
            return;
        }
        try {
            httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(config.getUrl()))
                    .timeout(Duration.ofSeconds(3))
                    .header(SESSION_HEADER, sessionId)
                    .DELETE()
                    .build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("MCP 会话终止请求失败（可忽略）：server={}, err={}", serverName, e.toString());
        }
        sessionId = null;
    }

    @Override
    public void close() {
        shutdown();
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = super.status();
        Map<String, Object> copy = new LinkedHashMap<>(status);
        copy.put("sessionId", sessionId == null ? null : sessionId.substring(0, Math.min(8, sessionId.length())) + "…");
        return copy;
    }
}
