package com.zifang.z.agent.proxy.spi;

import com.zifang.z.agent.kernel.agent.Agent;
import com.zifang.z.agent.kernel.agent.AgentRequest;
import com.zifang.z.agent.kernel.agent.AgentResponse;

import java.util.Map;

/**
 * 三方 agent 适配器 SPI.
 *
 * <p>实现方把 Cursor / Operator / Aider / Codeium / Continue 等三方 agent 的私有协议
 * 转译成 kernel.agent.Agent 的标准 SPI. 这样 z-agent 平台和 z-team 可以把"外部 agent"
 * 当作内部 agent 一样调度.
 *
 * <p>设计要点:
 * <ul>
 *   <li>三方 agent 通常是"外部进程 / 外部服务", 不在 JVM 内, 通过 RPC / HTTP / CLI 调用</li>
 *   <li>每次 call() 对应一次三方 agent 的"会话轮次", 由适配器负责会话生命周期</li>
 *   <li>能力描述(capabilities)告诉调度方该 agent 能干什么, 用于平台做路由</li>
 * </ul>
 */
public interface ThirdPartyAgentAdapter extends Agent {

    /**
     * @return 该三方 agent 的产品名(如 "cursor" / "aider" / "codeium")
     */
    String productName();

    /**
     * @return 该 adapter 的实例名(可配置, 用于多实例)
     */
    String adapterName();

    /**
     * @return 该三方 agent 暴露的能力集(如 "code-edit" / "browser-control" / "git-commit")
     */
    java.util.Set<String> capabilities();

    /**
     * @return 三方 agent 的 endpoint / 启动方式描述(用于 debug + 配置校验)
     */
    String endpoint();

    /**
     * 健康检查.
     */
    boolean isHealthy();

    /**
     * 关闭底层连接.
     */
    void close();
}