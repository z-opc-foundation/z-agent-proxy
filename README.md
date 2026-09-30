# z-agent-proxy

> 三方 coding agent 适配代理 —— 把 Aider CLI 子进程与 Cursor 的 OpenAI 兼容 HTTP 口包装成 `z-agent-kernel` 的 `kernel.agent.Agent` SPI

它解决的是"接入形状"问题：三方 agent 要么是本机子进程（Aider），要么是另一个端口上的 REST 服务（Cursor）。
上层平台（z-agent / z-team / z-bot）不想关心这个差别，也不想为每家私有协议重写一份调度代码。
本仓提供一个 SPI 和两份实现，把外部 agent 收敛成 `run(AgentRequest) → AgentResponse` 这一种形状。

**本仓是纯 jar 库，不是服务**：没有 `src/main/resources`、没有 `application*.yml`、没有 Controller、
不开任何监听端口。它只"往外调"（子进程 / HTTP），不"往里收"。

---

## 📋 基本信息

| 字段 | 值 | 怎么自己验一遍 |
|------|-----|----------------|
| **仓库** | `z-agent-proxy`（单模块 jar，无 reactor 子模块） | `grep -c '<module>' pom.xml` ⇒ `0` |
| **Maven 坐标** | `io.github.yuku123:z-agent-proxy:${revision}` | `grep -n '<artifactId>z-agent-proxy' pom.xml` |
| **当前版本** | `0.1.1`（根 POM `<properties><revision>`，唯一真源；CI-friendly + flatten） | `grep -n '<revision>' pom.xml` |
| **父项目** | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘） | `grep -n -A3 '<parent>' pom.xml` |
| **Maven Central** | **`0.1.1` 已发布**：`pom` / `jar` / `-sources.jar` / `-javadoc.jar` 及 `.asc` 齐，普通 GET `200`，带 Range 的 `-r 0-0` GET `206`；`0.1.0` 同目录也在（历史版本，见下节） | `curl -s -o /dev/null -w '%{http_code}\n' https://repo1.maven.org/maven2/io/github/yuku123/z-agent-proxy/0.1.1/z-agent-proxy-0.1.1.pom` |
| **默认端口** | 无（库形态，不起服务、不开端口；全仓无 `application*.yml`） | `find . -name 'application*' -not -path './target/*'` ⇒ 空 |
| **运行口径** | Java 8（`maven.compiler.source/target=8` + `-parameters`，全部由 parent 的 `pluginManagement` 下发）；**非 Spring Boot 应用**，`src/main` 里零 `org.springframework` import | `grep -c 'maven.compiler' pom.xml` ⇒ `0`；`grep -rn 'import org.springframework' src/main` ⇒ 空 |
| **最近更新** | 2026-09-30 | — |

---

## 🎯 能力清单

面只有一个接口：[`src/main/java/com/zifang/z/agent/proxy/spi/ThirdPartyAgentAdapter.java`](src/main/java/com/zifang/z/agent/proxy/spi/ThirdPartyAgentAdapter.java)（53 行），
它 `extends com.zifang.z.agent.kernel.agent.Agent`（`getName` / `run` / `streamRun` / `reset` 四件来自内核），
再额外要求 6 个方法：`productName()` / `adapterName()` / `capabilities()` / `endpoint()` / `isHealthy()` / `close()`。
`getName()` 的返回值固定为 `productName() + ":" + adapterName()`，供上层 dispatch 用。

实现共 **2** 个：

| adapter | 通道 | 真实出处 | 关键行为 |
|---|---|---|---|
| `AiderAdapter` | 本地子进程 | [`adapter/aider/AiderAdapter.java`](src/main/java/com/zifang/z/agent/proxy/adapter/aider/AiderAdapter.java)（191 行） | `ProcessBuilder(aiderPath, "--message", msg, "--no-auto-commits")`，cwd = `repoDir`，`redirectErrorStream(true)` 合并 stdout+stderr 一次读干，`waitFor(timeoutMs)` 超时即 `destroyForcibly()`；默认超时 `300_000ms`；`repoDir` 必须是已存在目录，否则构造期抛 `IllegalArgumentException` |
| `CursorAdapter` | HTTP（出方向） | [`adapter/cursor/CursorAdapter.java`](src/main/java/com/zifang/z/agent/proxy/adapter/cursor/CursorAdapter.java)（175 行） | OkHttp POST OpenAI 兼容体（`model=cursor-default`、`stream:false`）到 `endpoint`（缺省 `http://localhost:8765/v1/chat/completions`），`apiKey` 非空时加 `Authorization: Bearer <apiKey>`；解析 `choices[0].message.content` 与 `usage.prompt_tokens` / `usage.completion_tokens` 填 `TokenUsage`；connect 30s / read 300s |

复算 adapter 名单：`grep -rh 'productName() {' src/main` ⇒ 输出 `"aider"` 与 `"cursor"` 两行。
`capabilities()` 面值：Aider = `code-edit` / `git-commit` / `file-refactor`，Cursor = `code-edit` / `ide-aware` / `multi-file`。

消息取用逻辑（两个实现各有一份）：Aider 只取一条文本 —— 先看 `request.getInput()` 是否为 `USER` 且非空，
否则回扫 `history` 取最后一条 `USER`；Cursor 把 `history + input` 整列转成 OpenAI `messages`，
role 映射为 `user` / `assistant` / `system`，其它 role 兜底成 `user`。

---

## 🏗️ 项目结构

```
z-agent-proxy/
├── pom.xml                     # 单模块 POM：parent z-boot-parent:1.0.21，<revision>=0.1.1
├── LICENSE                     # MIT
├── README.md
└── src/
    ├── main/java/com/zifang/z/agent/proxy/
    │   ├── spi/ThirdPartyAgentAdapter.java          # SPI：Agent + 6 个额外方法
    │   └── adapter/
    │       ├── aider/AiderAdapter.java              # 子进程通道
    │       └── cursor/CursorAdapter.java            # HTTP 通道（OpenAI 兼容）
    └── test/java/com/zifang/z/agent/proxy/adapter/aider/AiderAdapterTest.java
```

`git ls-files` 只有 8 个文件（上面这些）。仓根另有 `target/` 与 `.flattened-pom.xml`，两者都由 [`.gitignore`](.gitignore)
忽略、属于 flatten-maven-plugin / Maven 的**构建产物**，不是源码，也不在本文档化范围内。
本仓**没有** `src/main/resources`、没有 `_doc/`、没有 `Dockerfile` / `docker-compose*.yml` / `deploy/` / `k8s/` / `Makefile`。

---

## 🔧 技术栈

全部取自已发布的 0.1.1 flatten pom（`<parent>` 计数 `0`、依赖都是字面版本，可直接当"发布形状"读）：

| 层级 | 技术 / 版本 | 来源 |
|------|------------|------|
| 语言 / 运行时 | Java 8 | parent `1.0.21` 下发 `maven.compiler.source/target=8` + `-parameters` |
| Agent SPI | `z-agent-kernel-agent` / `-message` / `-types` | 本仓 POM **不写 `<version>`**，面值由 `z-boot-fleet` 下发（见文末「与内核版本的关系」） |
| HTTP 客户端 | `com.squareup.okhttp3:okhttp:4.12.0` | fleet 地板；kotlin 四格在 `<dependencyManagement>` 里钉字面 `1.9.21`，拒静默降级 |
| JSON | `com.fasterxml.jackson.core:jackson-databind:2.18.6` | 同上 |
| 日志门面 | `org.slf4j:slf4j-api:2.0.16` | 本仓 POM **刻意留字面版本**：父链（spring-boot 2.7.18）给的是 `1.7.36`，删掉就是降级 |
| 测试 | `junit:junit:4.13.2`（test scope） | fleet 地板 |
| 构建 | Maven + `flatten-maven-plugin:1.5.0`（`flattenMode=oss`、`updatePomFile=true`，常开不挂 profile）+ `maven-release-plugin:3.1.1`（`tagNameFormat=v@{project.version}`） | `pom.xml` |

---

## 🚀 快速开始

```bash
mvn -o clean install -DskipTests   # 编译并装进本地仓库
mvn -o test                        # 3 支单测，不依赖网络、也不依赖本机装了 aider
```

第三方版本一律由 `z-boot-parent` → `z-boot-dependencies`（地板）+ `z-boot-fleet`（兄弟仓权威表）供给，
模块 POM 里不该再出现字面版本钉（`slf4j-api` 与 kotlin 四格是刻意保留的两处例外）。
若报解析不到 parent，先确认本地/镜像能取到 `io.github.yuku123:z-boot-parent:1.0.21`。

用法（两个 adapter 都是普通 Java 对象，没有自动装配、没有 `@Bean`）：

```java
// 子进程通道：aiderPath 为空时回退成裸命令名 "aider"（依赖 PATH）
ThirdPartyAgentAdapter aider = new AiderAdapter("local", "/usr/local/bin/aider", "/path/to/repo");

// HTTP 通道：endpoint / apiKey 都由调用方传入；apiKey 传 null 或空串即不带 Authorization 头
ThirdPartyAgentAdapter cursor = new CursorAdapter("ide", endpoint, apiKey);

AgentResponse resp = cursor.run(new AgentRequest(Msg.user("..."), null, null, null, null));
```

发布（需要签名密钥与 Central 账号，凭据一律从外部注入，禁止落进 POM/yml）：`mvn deploy -Pcentral`，
`central-publishing-maven-plugin:0.8.0`，`publishingServerId=central`，`autoPublish=true`
（缺 `autoPublish` 时 deployment 会停在 `VALIDATED` 永不公开，而 Maven 一路报 `BUILD SUCCESS` —— 0.1.1 首次发布就踩过）。

---

## 🔌 对外接口

**本仓不暴露任何 HTTP 端点**，因此没有路由表可列；对外面就是上面那三个 Java 类型。
唯一与网络有关的是 **出方向** 调用：

| 方向 | 目标 | 说明 |
|------|------|------|
| 出站 HTTP | `POST {endpoint}`，缺省 `http://localhost:8765/v1/chat/completions` | Cursor 的 OpenAI 兼容口；`endpoint` 由消费方构造器传入，README 不给生产地址 |
| 本地子进程 | `{aiderPath} --message <msg> --no-auto-commits`，cwd = `repoDir` | 缺省 `aiderPath` 为裸名 `aider` |

失败不会抛，而是变成 `AgentResponse.finishReason`：`invalid_input` / `timeout` / `non_zero_exit` /
`completed` / `io_error` / `interrupted`（Aider 侧），`completed` / `http_error` / `io_error`（Cursor 侧）。
`http_error` 分支会把上游状态码与响应体片段拼进 `Msg.assistant("cursor HTTP <code>: <body>")` ——
接日志时注意别把上游返回体原文落到对外可见处。

---

## 🔐 配置与凭据

实测结论：`src/main` 里 **没有** `System.getenv` / `System.getProperty` / `@Value` / `@ConfigurationProperties`
（`grep -rn 'getenv\|System.getProperty\|@Value\|ConfigurationProperties' src/main` 为空），也**没有**任何 yml/properties 文件。
所以本仓**不定义任何环境变量名或配置键**，所有接入参数都是构造器入参，由消费方（z-agent 平台 / starter）在运行时注入：

| 参数 | 所属 | 敏感 | 要求 |
|------|------|------|------|
| `adapterName` | 两者 | 否 | 实例名，进 `getName()` |
| `aiderPath` / `repoDir` / `timeoutMs` | `AiderAdapter` | 否 | `repoDir` 必须已存在；`timeoutMs` 缺省 300_000 |
| `endpoint` | `CursorAdapter` | 否（但属部署地址） | 生产必须由消费方覆盖缺省 localhost |
| `apiKey` | `CursorAdapter` | **是** | 只允许运行时注入（消费方自己的配置体系/环境/密钥管理）。**禁止写进 yml、代码常量、jar 或镜像层**，也禁止出现在本文档 |

---

## 🧪 测试

```bash
mvn -o test
```

现状（静态实测，本仓只有 1 个测试类）：`src/test` 下 **3 支 `@Test`**，全在
[`AiderAdapterTest`](src/test/java/com/zifang/z/agent/proxy/adapter/aider/AiderAdapterTest.java)（38 行）——
元信息断言、`repoDir` 为 null 时抛 `IllegalArgumentException`、`close()` 后 `isHealthy()` 转 false。
复算：`grep -rho '@Test' --include='*.java' src | wc -l` ⇒ `3`。

如实说明两点：

- **没有一支测试真起 aider 子进程，也没有一支真发 HTTP** ⇒ 两个 adapter 的 `run()` 主路径目前没有任何自动化验收。
  上面记录的 `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` 是 2026-09-27 的清树实测结果，本次 README 更新未复跑构建。
- `CursorAdapter` 零测试覆盖。

---

## ⚠️ 明确没做的（别当成已完成）

- **POM `<description>` 点名 5 家（Cursor / Operator / Aider / Codeium / Continue），实现只有 2 家**。
  Operator / Codeium / Continue **没有对应类**：`grep -rni 'codeium\|continue\|operator' --include='*.java' src`
  只命中 `ThirdPartyAgentAdapter` 的两处注释。
- `AiderAdapter.capabilities()` 报 `code-edit` / `git-commit` / `file-refactor`，但它自己给的命令行带
  **`--no-auto-commits`** ⇒ "git-commit" 这一项当前是被它禁掉的，能力描述与命令行互相打脸。
- **两个 `isHealthy()` 都不探底层**：`AiderAdapter` 只判 `!closed && repoDir.isDirectory()`（不看 aider 可执行文件在不在），
  `CursorAdapter` 只判 `!closed`（不发请求、不看连通性）。别拿它当"对方服务活着"的证据。
- **`streamRun()` 不是流式**：两个实现都是"先同步跑完 `run()`，再一次性回调 `onComplete`"，`onStep` 一次都不发。
  复算：`grep -rn 'onStep' src/main` 只命中两处方法签名，没有调用点。
- **`TokenUsage` 只有 Cursor 侧会填**，Aider 侧所有分支返回 `TokenUsage.empty()`。
- **下游"一行 import 集成"仍未兑现**：`z-boot/z-boot-integration-starters/z-boot-agent-proxy-starter`（`1.0.21`）
  只声明了对本仓的依赖；该模块 `src/` 下**只有 1 个文件**，且是为让 javadoc.jar 能生成而放的占位
  `package-info.java` —— 无 autoconfiguration、无 `@Bean`、无 `spring.factories`，所以没有任何能注入的 bean。
  复算：`find z-boot/z-boot-integration-starters/z-boot-agent-proxy-starter/src -type f | wc -l` ⇒ `1`。
- **组织内暂无代码级消费者**：`grep -rn --include='*.java' 'AiderAdapter\|CursorAdapter\|ThirdPartyAgentAdapter' .`
  去掉本仓后为空。它现在是"已发布但未被调用"的一方。

---

## 与内核版本的关系

kernel 三坐标在本仓 POM 不写 `<version>`，面值由 `z-boot-fleet` 下发。当前 fleet 的
`<z-agent-kernel.version>` 读数是 **`0.2.1`**（复算：`grep -n '<z-agent-kernel.version>' ../z-boot/z-boot-fleet/pom.xml`），
而 **`0.2.1` 确实已经在 repo1 上**：`z-agent-kernel-agent` / `-message` / `-types` 三个坐标的
`-0.2.1.pom` 带 Range GET 均为 `206`、`-0.2.1.jar` 普通 GET 均为 `200`（对照组 `0.2.0` 的 pom ⇒ `404`，
即 0.2.0 那一版没发出去，别照抄旧文档里"0.2.x 未上 Central"的说法）。

由此产生一个容易看漏的差别：

- **已发布的 `z-agent-proxy:0.1.1` pom 里，kernel 三格烤的是 `0.1.1`**（发布当时 fleet 的值）；
- **本地 reactor 现在构建出的 flatten pom 里，三格是 `0.2.1`**。

也就是说，从 Central 拉 `0.1.1` 和从本机 `mvn install` 拿到的产物，绑的内核不是同一版。
要针对新内核构建，直接走本地源码依赖即可（fleet 已指到 0.2.1），无需 `-D` 覆盖：

```bash
mvn -o test                                  # 按 fleet 面值 0.2.1 解析内核
mvn -o test -Dz-agent-kernel.version=0.1.1   # 复现 Central 0.1.1 发布时的烤定形状
```

另注：`dependency:tree` 遇到取不到的 pom 只打 `The POM for ... is missing, no dependency information available`
的 WARNING 就继续报 `BUILD SUCCESS`，别拿它当"这件存在"。

---

## 📄 License

MIT，见仓库根 [`LICENSE`](LICENSE)；根 POM `<licenses>` 同样声明 MIT License。

_Maintained by the z-opc-foundation organization._
