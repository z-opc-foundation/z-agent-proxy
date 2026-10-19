# 项目总览

## 1. 项目简介

z-agent-proxy 是**三方 coding agent 的适配代理库**：把「本机子进程型 agent」（Aider CLI）与「另一端口上的 REST 服务」（Cursor 的 OpenAI 兼容口）统一包装成 `z-agent-kernel` 的 `kernel.agent.Agent` SPI。

上层平台（z-agent / z-team / z-bot）因此不必为每家三方 agent 的私有协议重写一份调度代码，只要拿到 `AgentResponse` 就能当内部 agent 一样路由。

它**不是服务**：无 `src/main/resources`、无 `application*.yml`、无 Controller、不监听任何端口。它只「往外调」（子进程 / 出方向 HTTP），不「往里收」。

**技术栈：** Java 8 · OkHttp 4.12.0（HTTP 通道）· Jackson 2.18.6 · SLF4J 2.0.16 · JUnit 4 · 零 Spring

## 2. 模块架构

单模块 Maven 工程（根 POM `<modules>` 为空），源码只有三个主类 + 一个测试类：

```
z-agent-proxy/
├── pom.xml                     # parent z-boot-parent:1.1.0，<revision>0.1.1</revision>，flatten oss
├── LICENSE                     # MIT
├── README.md
├── _frontend/
│   └── z-agent-proxy-suit/     # React 19 + antd 6 + Vite 6 管理台壳（"private": true，不发布 npm）
│       ├── index.html  vite.config.js  package.json
│       ├── public/icon.png
│       └── src/                # App.jsx / HomePage.jsx / LoginPage.jsx / StatusPage.jsx / main.jsx …
└── src/
    ├── main/java/com/zifang/z/agent/proxy/
    │   ├── spi/ThirdPartyAgentAdapter.java       # SPI：extends kernel Agent + 6 个额外方法
    │   └── adapter/
    │       ├── aider/AiderAdapter.java           # 子进程通道
    │       └── cursor/CursorAdapter.java         # HTTP 通道（OpenAI 兼容）
    └── test/java/com/zifang/z/agent/proxy/adapter/aider/AiderAdapterTest.java
```

## 3. 核心功能

### 3.1 适配器 SPI（`spi/ThirdPartyAgentAdapter.java`，53 行）

`ThirdPartyAgentAdapter extends com.zifang.z.agent.kernel.agent.Agent` —— `getName` / `run` / `streamRun` / `reset` 四件来自 kernel，本接口再额外要求 6 个方法：`productName()`（产品名，如 `aider` / `cursor`）、`adapterName()`（实例名，供多实例区分）、`capabilities()`（能力集，供平台路由）、`endpoint()`（启动方式 / 地址描述，用于 debug 与配置校验）、`isHealthy()`、`close()`。

`getName()` 的返回值固定为 `productName() + ":" + adapterName()`，供上层 dispatch。

### 3.2 Aider 适配器（子进程通道，191 行）

`ProcessBuilder(aiderPath, "--message", <msg>, "--no-auto-commits")` 拉起 CLI，工作目录设为 `repoDir`，`redirectErrorStream(true)` 把 stdout 与 stderr 合并后一次读干；`waitFor(timeoutMs)` 超时即 `destroyForcibly()`。默认超时 `300_000ms`（两个重载构造器都落到该缺省值）；`repoDir` 必须是已存在目录，否则构造期抛 `IllegalArgumentException`。

消息取用：只取一条文本 —— 先看 `request.getInput()` 是否为 `USER` 且非空，否则回扫 `history` 取最后一条 `USER`。

能力集 `capabilities()` 为 `code-edit` / `git-commit` / `file-refactor`。

### 3.3 Cursor 适配器（HTTP 通道，175 行）

OkHttp POST OpenAI 兼容请求体（`model=cursor-default`、`stream=false`）到 `endpoint`，缺省 `http://localhost:8765/v1/chat/completions`；`apiKey` 非空时加 `Authorization: Bearer <apiKey>` 头。connect 超时 30s、read 超时 300s。

响应解析 `choices[0].message.content`，并用 `usage.prompt_tokens` / `usage.completion_tokens` 填 `TokenUsage`。历史消息 `history + input` 整列转成 OpenAI `messages`，role 映射 `user` / `assistant` / `system`，其它 role 兜底成 `user`。

能力集为 `code-edit` / `ide-aware` / `multi-file`。

### 3.4 失败即状态，不抛异常

两个实现都把失败折成 `AgentResponse.finishReason`，不向调用方抛：Aider 侧为 `invalid_input` / `timeout` / `non_zero_exit` / `completed` / `io_error` / `interrupted`；Cursor 侧为 `completed` / `http_error` / `io_error`。`http_error` 分支会把上游状态码与响应体片段拼进 `Msg.assistant("cursor HTTP <code>: <body>")`。

## 4. 对外集成 / 上下游

**本仓不暴露任何 HTTP 端点**（无 Controller、无监听端口），对外就是三个 Java 类型。

**唯一与网络有关的是出方向调用：**

| 方向 | 目标 | 说明 |
|---|---|---|
| 出站 HTTP | `POST {endpoint}`，缺省 `http://localhost:8765/v1/chat/completions` | Cursor 的 OpenAI 兼容口；`endpoint` 由消费方构造器传入，生产必须覆盖缺省 localhost |
| 本地子进程 | `{aiderPath} --message <msg> --no-auto-commits`，cwd = `repoDir` | 缺省 `aiderPath` 为裸命令名 `aider`（依赖 PATH） |

**上游依赖（取自 `pom.xml`）：**

| 依赖 | 版本 | 作用 |
|---|---|---|
| `z-agent-kernel-agent` / `-message` / `-types` | 不写版本，由 `z-boot-fleet` 下发 | kernel 的 `Agent` / `AgentRequest` / `AgentResponse` / `Msg` 契约 |
| `com.squareup.okhttp3:okhttp` | 4.12.0（fleet 地板） | Cursor 的 HTTP 通道 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.6（fleet 地板） | JSON 编解码 |
| `org.slf4j:slf4j-api` | **2.0.16（本仓刻意写字面版本）** | 日志门面；父链（spring-boot 2.7.18）给的是 1.7.36，删掉即降级 |
| `io.github.yuku123:z-util-{core,parser-json,dsl-kernel}` | 1.2.2 | 内部工具库 |
| `kotlin-stdlib{,-common,-jdk7,-jdk8}` | 1.9.21 | 钉在 `dependencyManagement`，拒静默降级 |
| `junit:junit` | test scope | 唯一的测试类用它 |

**配置与凭据：** `src/main` 里无 `System.getenv` / `System.getProperty` / `@Value` / `@ConfigurationProperties`，也无任何 yml/properties 文件 —— 本仓**不定义任何环境变量名或配置键**，全部接入参数都是构造器入参，由消费方在运行时注入。

**下游消费方：** 两个 adapter 都是普通 Java 对象，没有自动装配、没有 `@Bean`；调用方自行 `new` 后当 `Agent` 用。

## 5. 技术特点

1. **一个 SPI、两种通道**：子进程与 HTTP 的差异全部封在两个 adapter 内，对上层只暴露 kernel 的 `Agent` 形状（`run` / `streamRun` / `reset` / `getName`）。
2. **失败即状态码**：不抛异常，全部折成 `AgentResponse.finishReason`，上层调度器可以按状态码重试或降级，不必 catch 异常分类。
3. **配置全走构造器**：仓内零环境变量读取、零配置文件，`apiKey` 只能由消费方运行时注入，天然避免密钥落进代码常量、jar 或镜像层。
4. **无 Spring 依赖**：`src/main` 里 `import org.springframework` 命中 0 处，因此本库能被非 Spring 宿主直接使用；代价是**没有任何 starter 可用**。
5. **`isHealthy()` 不探底层**：Aider 侧只判 `!closed && repoDir.isDirectory()`（不看 aider 可执行文件在不在），Cursor 侧只判 `!closed`（不发请求、不看连通性）—— 不能当「对方服务活着」的证据。
6. **`streamRun()` 不是流式**：两个实现都是「先同步跑完 `run()`，再一次性回调 `onComplete`」，`onStep` 一次都不发。
7. **发布形态自包含**：`flatten-maven-plugin` 1.5.0 用 `flattenMode=oss`（剥掉 `<parent>`、版本落成字面量），`central-publishing-maven-plugin` 0.8.0 带 `autoPublish=true` 发布。

## 6. 目录结构

本仓无 Java 多模块结构、无前端资源目录，源码按「SPI / 适配器 / 测试」三段组织，Java 包根 `com.zifang.z.agent.proxy`：

```
z-agent-proxy/
├── pom.xml
├── src/
│   ├── main/java/com/zifang/z/agent/proxy/
│   │   ├── spi/ThirdPartyAgentAdapter.java        # SPI 定义（extends kernel Agent）
│   │   └── adapter/
│   │       ├── aider/AiderAdapter.java            # 子进程通道实现
│   │       └── cursor/CursorAdapter.java          # HTTP 通道实现
│   └── test/java/com/zifang/z/agent/proxy/adapter/aider/AiderAdapterTest.java
└── _frontend/z-agent-proxy-suit/                  # React 管理台壳（不发布 npm）
```

规模实测：`src/main` 3 个 `.java` 共 419 行（`AiderAdapter` 191 / `CursorAdapter` 175 / `ThirdPartyAgentAdapter` 53），`src/test` 1 个 `.java` 38 行、3 支 `@Test`（全部在 Aider 侧，Cursor 侧零测试覆盖）。
