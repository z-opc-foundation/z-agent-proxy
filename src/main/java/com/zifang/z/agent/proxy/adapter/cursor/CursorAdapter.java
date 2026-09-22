package com.zifang.z.agent.proxy.adapter.cursor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.agent.kernel.agent.AgentRequest;
import com.zifang.z.agent.kernel.agent.AgentResponse;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.agent.proxy.spi.ThirdPartyAgentAdapter;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Cursor 适配器 — 把 Cursor IDE 的 REST API 包成 kernel.agent.Agent.
 *
 * <p>Cursor 通过其内部 REST endpoint 暴露 chat/completions, 接受 OpenAI 兼容请求体.
 * <p>注意: 此 adapter 需要 Cursor 暴露远程访问入口, 默认是 localhost:port.
 *
 * <p>设计:
 * <ul>
 *   <li>messages: 把 kernel Msg 列表转成 OpenAI 格式</li>
 *   <li>stream: false(简化实现), cursor 完整回复后返回</li>
 *   <li>capabilities: code-edit, ide-aware, multi-file</li>
 * </ul>
 */
public class CursorAdapter implements ThirdPartyAgentAdapter {

    private static final Logger log = LoggerFactory.getLogger(CursorAdapter.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String adapterName;
    private final String endpoint;
    private final String apiKey;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean closed = false;

    public CursorAdapter(String adapterName, String endpoint, String apiKey) {
        this.adapterName = adapterName;
        this.endpoint = endpoint == null ? "http://localhost:8765/v1/chat/completions" : endpoint;
        this.apiKey = apiKey;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() { return productName() + ":" + adapterName; }

    @Override
    public String productName() { return "cursor"; }

    @Override
    public String adapterName() { return adapterName; }

    @Override
    public Set<String> capabilities() {
        Set<String> caps = new HashSet<String>();
        caps.add("code-edit");
        caps.add("ide-aware");
        caps.add("multi-file");
        return Collections.unmodifiableSet(caps);
    }

    @Override
    public String endpoint() { return endpoint; }

    @Override
    public boolean isHealthy() { return !closed; }

    @Override
    public void close() { closed = true; }

    @Override
    public void reset() {
        // Cursor 是无状态的 HTTP 调用, reset 为 no-op
    }

    @Override
    public void streamRun(AgentRequest request,
                          java.util.function.Consumer<AgentResponse.Step> onStep,
                          java.util.function.Consumer<AgentResponse> onComplete,
                          java.util.function.Consumer<Throwable> onError) {
        try {
            AgentResponse resp = run(request);
            if (onComplete != null) onComplete.accept(resp);
        } catch (RuntimeException e) {
            if (onError != null) onError.accept(e);
            else throw e;
        }
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        if (closed) throw new IllegalStateException("CursorAdapter closed");
        try {
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("model", "cursor-default");
            body.put("messages", convertMessages(buildMessages(request)));
            body.put("stream", false);

            Request.Builder rb = new Request.Builder()
                    .url(endpoint)
                    .post(RequestBody.create(mapper.writeValueAsBytes(body), JSON));
            if (apiKey != null && !apiKey.isEmpty()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }
            try (Response resp = http.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful()) {
                    String err = resp.body() == null ? "(no body)" : resp.body().string();
                    return new AgentResponse(
                            Msg.assistant("cursor HTTP " + resp.code() + ": " + err),
                            Collections.<AgentResponse.Step>emptyList(),
                            TokenUsage.empty(), "http_error", null, null);
                }
                JsonNode root = mapper.readTree(resp.body().bytes());
                String content = root.path("choices").path(0).path("message").path("content").asText("");
                long promptTokens = root.path("usage").path("prompt_tokens").asLong(0L);
                long completionTokens = root.path("usage").path("completion_tokens").asLong(0L);
                long totalTokens = promptTokens + completionTokens;
                TokenUsage usage = new TokenUsage(promptTokens, completionTokens, totalTokens);
                log.info("CursorAdapter[{}]: {} tokens (prompt={}, completion={})",
                        adapterName, totalTokens, promptTokens, completionTokens);
                return new AgentResponse(
                        Msg.assistant(content),
                        Collections.<AgentResponse.Step>emptyList(),
                        usage, "completed", null, null);
            }
        } catch (IOException e) {
            return new AgentResponse(
                    Msg.assistant("cursor IO error: " + e.getMessage()),
                    Collections.<AgentResponse.Step>emptyList(),
                    TokenUsage.empty(), "io_error", e, null);
        }
    }

    private List<Msg> buildMessages(AgentRequest request) {
        List<Msg> all = new ArrayList<Msg>();
        if (request == null) return all;
        if (request.getHistory() != null) all.addAll(request.getHistory());
        if (request.getInput() != null) all.add(request.getInput());
        return all;
    }

    private List<Map<String, Object>> convertMessages(List<Msg> messages) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (messages == null) return out;
        for (Msg m : messages) {
            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("role", m.getRole() == MessageRole.USER ? "user"
                    : m.getRole() == MessageRole.ASSISTANT ? "assistant"
                    : m.getRole() == MessageRole.SYSTEM ? "system" : "user");
            entry.put("content", m.getContent() == null ? "" : m.getContent());
            out.add(entry);
        }
        return out;
    }
}