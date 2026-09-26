# z-agent-proxy

把**三方 coding agent**（Aider CLI、Cursor 的 OpenAI 兼容 HTTP 口）包装成 `z-agent-kernel` 的
`kernel.agent.Agent` SPI，让上层平台（z-agent / z-team / z-bot）不需要知道对方是子进程还是 REST 服务。

| 项 | 值 | 怎么自己验一遍 |
|---|---|---|
| 坐标 | `io.github.yuku123:z-agent-proxy` | `grep -n '<artifactId>z-agent-proxy' pom.xml` |
| 当前源码版本 | `0.1.0`（`<revision>`，唯一真源） | `grep -n '<revision>' pom.xml` |
| Central 上实际有的版本 | `0.1.0`（2026-09-27 实测；清单会变，要信命令别信这一行） | `curl -s https://repo1.maven.org/maven2/io/github/yuku123/z-agent-proxy/maven-metadata.xml \| grep -o '<version>[^<]*'` |
| 模块结构 | 单模块 jar，无子模块 | `grep -c '<module>' pom.xml`（输出 `0`） |
| JDK | Java 8 | `grep -n 'maven.compiler' pom.xml` |
| 内核 pin | `z-agent-kernel.version = ${revision}` ⇒ 跟着本仓版本号走（当前 0.1.0） | `grep -n 'z-agent-kernel.version' pom.xml` |
| 源码规模 | main 3 个 `.java` / test 1 个 | `find src -name '*.java' \| grep -c 'src/main/'` 与 `.../grep -c 'src/test/'` |
| 测试数 | **3 支 `@Test`**（文本口径，全部在 `AiderAdapterTest`） | `grep -rho '@Test' --include='*.java' src \| wc -l` |

**编译与测试**

```bash
rm -rf target && mvn -o test   # 3 支，全部不依赖网络、不依赖本机装了 aider
```

2026-09-27 04:4x 清树实测：`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`
（1 个测试类 `AiderAdapterTest`）。

## 面只有一个接口

`spi/ThirdPartyAgentAdapter.java`（53 行）继承 `kernel.agent.Agent`，额外要求 6 个方法：
`productName()` / `adapterName()` / `capabilities()` / `endpoint()` / `isHealthy()` / `close()`。
复算：`grep -n ');$' src/main/java/com/zifang/z/agent/proxy/spi/ThirdPartyAgentAdapter.java`。

实现共 **2** 个：

| adapter | 通道 | 真实出处 | 关键行为 |
|---|---|---|---|
| `AiderAdapter` | 本地子进程 | `adapter/aider/AiderAdapter.java`（191 行） | `ProcessBuilder(aiderPath, "--message", msg, "--no-auto-commits")`，cwd = `repoDir`，stdout+stderr 合并读干，`waitFor(timeoutMs)` 超时即 `destroyForcibly()`；默认超时 `300_000ms` |
| `CursorAdapter` | HTTP | `adapter/cursor/CursorAdapter.java`（175 行） | OkHttp POST OpenAI 兼容体到 `endpoint`（缺省 `http://localhost:8765/v1/chat/completions`），`stream:false`，解析 `choices[0].message.content` + `usage.*_tokens` |

复算 adapter 名单：`grep -rh 'productName() {' src/main` ⇒ 输出 `"aider"` 与 `"cursor"` 两行。

## 明确没做的（别当成已完成）

- **pom 的 `<description>` 点名 5 家（Cursor / Operator / Aider / Codeium / Continue），实现只有 2 家**（复算见上一节）。
  Operator / Codeium / Continue **没有对应类**，`grep -rn 'codeium\|continue\|operator' --include='*.java' src -i` 只命中注释。
- `AiderAdapter.capabilities()` 报 `code-edit` / `git-commit` / `file-refactor` 三项，但它自己给的命令行带
  **`--no-auto-commits`** ⇒ "git-commit" 这一项当前是被它禁掉的，能力描述与命令行互相打脸（`AiderAdapter.java` 内
  `capabilities()` 与 `run()` 的 `ProcessBuilder` 两处对照）。
- **两个 `isHealthy()` 都不探底层**：`AiderAdapter` 只判 `!closed && repoDir.isDirectory()`（不看 aider 可执行文件在不在），
  `CursorAdapter` 只判 `!closed`（不发请求、不看连通性）。别拿它当"对方服务活着"的证据。
- **`streamRun()` 不是流式**：两个实现都是"先同步跑完 `run()`，再一次性回调 `onComplete`"，`onStep` 一次都不发。
  复算：`grep -n 'onStep' src/main --include='*.java' -r` 只命中方法签名，没有调用点。
- **`TokenUsage` 只有 Cursor 侧会填**，Aider 侧所有分支返回 `TokenUsage.empty()`。
- **`CursorAdapter` 零测试**：3 支 `@Test` 全在 `AiderAdapterTest`，且只覆盖元信息 / `repoDir` 校验 / `close()` 后置为不健康，
  **没有一支真起 aider 子进程**，也没有一支真发 HTTP。⇒ 两个 adapter 的 `run()` 主路径目前没有任何自动化验收。
- 下游现状：`z-boot/z-boot-integration-starters/z-boot-agent-proxy-starter` 只声明了对本仓的依赖，
  **该目录里没有 `src/`**（只有 `pom.xml` 与构建产物 `.flattened-pom.xml`；无 autoconfiguration、无 `@Bean`），
  所以"一行 import 集成"这句还没兑现成能注入的 bean。复算：
  `find ../z-boot/z-boot-integration-starters/z-boot-agent-proxy-starter -type f -not -path '*/target/*'`
  ⇒ 输出两行，都不是 `.java`。

## 与内核版本的关系

本仓 pin 的是 `${revision}`=0.1.0，而 `z-agent-kernel` 源码树已到 `0.2.1`（`../z-agent-kernel/pom.xml` 的 `<revision>`）。
两边不冲突的前提是你**本机 m2 里有 0.1.0**（或从 Central 拉）。要针对 0.2.x 内核构建，显式覆盖：

```bash
mvn -o test -Dz-agent-kernel.version=0.2.1   # 需先在内核仓 mvn -o install，见其 README
```

注意 0.2.x 目前**没有发 Central**（复算命令见上表第一行"Central 上实际有的版本"），
所以 `-Dz-agent-kernel.version=0.2.1` 只在装过该版本的本机能成。

## 许可

MIT，见 [`LICENSE`](LICENSE)。
