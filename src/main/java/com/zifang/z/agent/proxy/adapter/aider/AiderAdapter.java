package com.zifang.z.agent.proxy.adapter.aider;

import com.zifang.z.agent.kernel.agent.AgentRequest;
import com.zifang.z.agent.kernel.agent.AgentResponse;
import com.zifang.z.agent.kernel.message.Msg;
import com.zifang.z.agent.kernel.types.MessageRole;
import com.zifang.z.agent.kernel.types.TokenUsage;
import com.zifang.z.agent.proxy.spi.ThirdPartyAgentAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Aider 适配器 — 把 Aider CLI 包成 kernel.agent.Agent.
 *
 * <p>Aider 是 git-aware 的 AI coding 工具, 通过命令行跟 Python 子进程交互.
 *
 * <p>用法:
 * <pre>{@code
 * ThirdPartyAgentAdapter agent = new AiderAdapter("aider", "/usr/local/bin/aider", "/path/to/repo");
 * AgentResponse resp = agent.run(new AgentRequest(Msg.user("..."), null, null, null, null));
 * }</pre>
 *
 * <p>限制:
 * <ul>
 *   <li>Aider CLI 是同步阻塞的, 每次调用最长 5 分钟(默认), 超时杀进程</li>
 *   <li>Aider 主要做 code edit + git commit, 不擅长通用对话 — capabilities 明确标注</li>
 *   <li>需要 git repo 路径(repoDir 不能为空)</li>
 * </ul>
 */
public class AiderAdapter implements ThirdPartyAgentAdapter {

    private static final Logger log = LoggerFactory.getLogger(AiderAdapter.class);

    private final String adapterName;
    private final String aiderPath;
    private final File repoDir;
    private final long timeoutMs;
    private volatile boolean closed = false;

    public AiderAdapter(String adapterName, String aiderPath, String repoDir) {
        this(adapterName, aiderPath, repoDir == null ? null : new File(repoDir), 300_000L);
    }

    public AiderAdapter(String adapterName, String aiderPath, File repoDir) {
        this(adapterName, aiderPath, repoDir, 300_000L);
    }

    public AiderAdapter(String adapterName, String aiderPath, File repoDir, long timeoutMs) {
        if (repoDir == null || !repoDir.isDirectory()) {
            throw new IllegalArgumentException("repoDir must be an existing directory: " + repoDir);
        }
        this.adapterName = adapterName;
        this.aiderPath = aiderPath == null ? "aider" : aiderPath;
        this.repoDir = repoDir;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String getName() { return productName() + ":" + adapterName; }

    @Override
    public String productName() { return "aider"; }

    @Override
    public String adapterName() { return adapterName; }

    @Override
    public Set<String> capabilities() {
        Set<String> caps = new HashSet<String>();
        caps.add("code-edit");
        caps.add("git-commit");
        caps.add("file-refactor");
        return Collections.unmodifiableSet(caps);
    }

    @Override
    public String endpoint() { return aiderPath + " (cwd=" + repoDir.getAbsolutePath() + ")"; }

    @Override
    public boolean isHealthy() {
        return !closed && repoDir.isDirectory();
    }

    @Override
    public void close() { closed = true; }

    @Override
    public void reset() {
        // Aider 每次 call() 启动独立子进程, 没有持久会话状态, reset 为 no-op
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
        if (closed) {
            throw new IllegalStateException("AiderAdapter closed");
        }
        String userMessage = extractUserMessage(request);
        if (userMessage == null || userMessage.isEmpty()) {
            return new AgentResponse(
                    Msg.assistant("aider requires non-empty user message"),
                    Collections.<AgentResponse.Step>emptyList(),
                    TokenUsage.empty(), "invalid_input", null, null);
        }

        ProcessBuilder pb = new ProcessBuilder(aiderPath, "--message", userMessage, "--no-auto-commits");
        pb.directory(repoDir);
        pb.redirectErrorStream(true);

        log.info("AiderAdapter[{}]: invoking aider in {}", adapterName, repoDir.getAbsolutePath());

        Process process = null;
        StringBuilder output = new StringBuilder();
        try {
            process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new AgentResponse(
                        Msg.assistant("aider timeout (" + timeoutMs + "ms): " + output),
                        Collections.<AgentResponse.Step>emptyList(),
                        TokenUsage.empty(), "timeout", null, null);
            }
            int exitCode = process.exitValue();
            log.info("AiderAdapter[{}]: exit={}, output_len={}", adapterName, exitCode, output.length());
            String reason = exitCode == 0 ? "completed" : "non_zero_exit";
            return new AgentResponse(
                    Msg.assistant(output.toString()),
                    Collections.<AgentResponse.Step>emptyList(),
                    TokenUsage.empty(), reason, null, null);
        } catch (IOException e) {
            if (process != null) process.destroyForcibly();
            return new AgentResponse(
                    Msg.assistant("aider IO error: " + e.getMessage()),
                    Collections.<AgentResponse.Step>emptyList(),
                    TokenUsage.empty(), "io_error", e, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            return new AgentResponse(
                    Msg.assistant("aider interrupted"),
                    Collections.<AgentResponse.Step>emptyList(),
                    TokenUsage.empty(), "interrupted", e, null);
        }
    }

    private String extractUserMessage(AgentRequest request) {
        if (request == null) return null;
        Msg input = request.getInput();
        if (input != null && input.getRole() == MessageRole.USER && input.getContent() != null
                && !input.getContent().isEmpty()) {
            return input.getContent();
        }
        if (request.getHistory() == null) return null;
        Msg last = null;
        for (Msg m : request.getHistory()) {
            if (m.getRole() == MessageRole.USER) {
                last = m;
            }
        }
        return last == null ? null : last.getContent();
    }
}