# 观测 Web 平台

## 目的与边界

`agent-observability-web` 是一个独立的 Next.js 服务，用于处理 SDK 的版本化 `AgentTrace` 文档。它提供了一个实用的本地单节点 MVP，而无需将 `agent-core` 变成监控服务器或添加 Java Web 框架依赖。

第一个版本包含：

- `POST /api/traces` 摄取接口，支持 schema 验证、可选的 Bearer 身份验证，以及 2 MiB 的请求体限制；
- `GET /api/traces` 和 `GET /api/traces/{turnId}` 查询端点；
- `/api/applications` 下的应用程序 CRUD 和一次性 API 密钥生成/轮换功能；
- `GET /api/health` 就绪状态信息；
- 按调用方触发的 Task 分组的概览，包含成功率、P95 耗时、Tokens、Tool 错误、应用程序/状态/Agent 过滤，以及手动/自动刷新功能；
- 中英文界面，默认选择中文，并在本地持久化保存浏览器的选择；
- Task 详情页，包含聚合使用量、所有参与的 Agent、关联字段、合并的 Parent/SubAgent 调用图、可点击的节点请求和响应检查器、独立的 Provider/SDK 负载选项卡，以及 Task/Turn/Step/Model/Tool 的 span 瀑布流；
- 仪表板支持单个和多选 Task 删除，包括属于每个选定 Task 的所有 Turn 追踪片段；
- 小型 `TraceStore` 接口支持原子化的本地文件持久化。

此 Web 项目刻意独立于 Maven reactor 之外。Java 8 仍然是 SDK 的运行时要求；仪表板使用 Node.js 20.9 或更新版本。

## 本地运行

```bash
cd agent-observability-web
npm ci
npm run dev
```

打开 `http://localhost:3000/applications`，创建一个应用程序，并保存平台显示的 API 密钥。明文仅显示一次。然后在 Java Agent 上注册平台观测：

```java
AgentObservability observability = AgentObservability.platform(
    "http://localhost:3000/api/traces",
    System.getenv("AGENT_OBSERVABILITY_API_KEY")
);

Agent agent = Agent.builder()
    .name("assistant")
    .model(model)
    .plugin(observability)
    .build();
```

当应用程序关闭时，请关闭 `observability`，以便其异步队列可以排空。

## 应用程序和摄取密钥

每个注册的应用程序都有一个独立生成的摄取密钥。创建或轮换应用程序会生成一个 `aoh_...` Bearer 密钥，由 32 个密码学安全的随机字节提供支持。平台仅存储其 SHA-256 哈希值和简短的显示提示；关闭一次性对话框后，明文将无法恢复。

应用程序的生命周期行为被设计为可预测的：

- 创建第一个应用程序会禁用匿名的 Trace 摄取；
- 轮换密钥会立即让上一个密钥失效；
- 删除应用程序会立即让其密钥失效；
- 删除操作不会级联到历史 Trace 的删除；
- 每个接收到的 Trace 都会获得一个服务端的应用程序 ID 和名称快照，因此过滤和历史归因不依赖于可变的 SDK 输入。

应用程序页面支持创建、读取、编辑、删除和密钥轮换。可用于自动化的等效管理端点如下：

```text
GET    /api/applications
POST   /api/applications
GET    /api/applications/{id}
PATCH  /api/applications/{id}
DELETE /api/applications/{id}
POST   /api/applications/{id}/rotate-key
```

之前的 `AGENT_OBSERVABILITY_API_KEY` 服务环境变量仍作为迁移的遗留全局摄取密钥保留。它不标识任何应用程序，因此新部署应优先使用生成的应用程序密钥。

## 管理员身份验证

应用程序管理会更改访问凭证，在生产环境中必须受到保护。生成一个管理员密钥：

```bash
cd agent-observability-web
npm run --silent generate-key

# Equivalent alternative:
openssl rand -hex 32
```

仅在 Web 服务上进行配置 (`agent-observability-web/.env.local`)：

```dotenv
AGENT_OBSERVABILITY_ADMIN_KEY=the-generated-administrator-secret
```

配置后，管理页面需要管理员登录，并存储一个从配置的密钥派生出来的、具有 HTTP-only 和 SameSite=Strict 属性的会话令牌。原始的管理员密钥不会放入会话 Cookie 中。当没有该变量时，管理功能对于本地开发是开放的。管理 API 自动化可以直接发送管理员密钥作为 `Authorization: Bearer ...` 请求头，而不是创建浏览器会话。

将每个生成的应用程序密钥存储在对应应用程序的密钥管理器中，并提供给其 Java 进程：

```bash
export AGENT_OBSERVABILITY_MODE=PLATFORM
export AGENT_OBSERVABILITY_ENDPOINT="http://localhost:3000/api/traces"
export AGENT_OBSERVABILITY_API_KEY="the-application-key-shown-once"
```

```java
AgentObservability observability = AgentObservability.platform(
    "http://localhost:3000/api/traces",
    System.getenv("AGENT_OBSERVABILITY_API_KEY")
);
```

不要将管理员或应用程序密钥添加到 Git 或日志中。在反向代理或托管平台处终止 TLS。在这个 MVP 中，Trace 仪表板和 GET 查询 API 在没有管理员会话的情况下仍然可读；如果 Trace 数据不能公开，请将整个服务放置在经过身份验证的网关之后。

Trace 的删除是永久性的，并且使用与应用程序管理相同的管理员身份验证。浏览器请求还必须通过同源检查。每条记录由应用程序 ID 和 Turn ID 共同寻址，因此删除一个应用程序的 Turn 不会移除具有相同 Turn ID 的另一个应用程序的记录。批处理 API 每次请求最多接受 500 个标识；仪表板会自动将更大的选择拆分为有限的请求。

```text
DELETE /api/traces/{turnId}?applicationId={applicationId}
DELETE /api/traces
```

批处理请求体为 `{ "traces": [{ "turnId": "...", "applicationId": "..." }] }`。

仪表板将调用方触发时创建的一个根 Turn 视为一个 **Task**。作为后代的 Agent-as-Tool Turn 通过 `parentTurnId` 进行连接，因此一个 Supervisor 及其所有 SubAgent 显示为一行，即使接收方仍然将它们不可变的 Turn 文档分开存储。Task 级别的 Step、Model 调用、Tool 调用、错误、流式事件和 Token 使用量是对这些文档的总和。状态和实际挂钟持续时间来自根 Turn，因为这是人类调用者所观察到的结果。

原始的 Turn 级别查询和删除 API 保持不变。当在仪表板中删除一个 Task 时，它会将 Task 展开为其包含的 Turn 标识，并使用现有的受限批处理 API。这保持了与现有集成和已存储的 schema 版本的兼容性。

## 配置

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `AGENT_OBSERVABILITY_ADMIN_KEY` | empty | 启用管理员登录以进行应用程序 CRUD 和密钥轮换 |
| `AGENT_OBSERVABILITY_API_KEY` | empty | 没有应用程序归因的遗留全局摄取密钥 |
| `AGENT_OBSERVABILITY_SECURE_COOKIES` | automatic | 设置为 `true` 强制将管理员会话 Cookie 设为 HTTPS-only |
| `AGENT_OBSERVABILITY_DATA_DIR` | Web 项目中的 `.data` | 绝对或相对的 Trace 数据目录 |
| `AGENT_OBSERVABILITY_RETENTION` | `5000` | 保留的本地 Trace 文档最大数量 |

Trace 文件名是应用程序 ID 和 Turn ID 的 SHA-256 哈希值。应用程序记录存储的是密钥哈希，绝不是明文密钥。两种存储都使用临时文件加重命名的方式，以便读取者不会看到部分写入的数据。在操作系统支持 POSIX 模式的地方，创建的文件具有仅所有者权限。

## 存储和部署

本地 `TraceStore` 故意针对 MVP 进行了优化：

- 适用于本地开发和一个长时间运行的 Node.js 实例；
- 它在该进程内部串行化写入并修剪最旧的文件；
- 它不协调多个副本；
- 临时/无服务器文件系统可能会在重启后丢弃其数据；
- 列表操作会扫描保留的文件，不适用于数以百万计的 Trace。

对于生产或水平扩展，请使用 PostgreSQL、ClickHouse、对象存储或遥测后端实现相同的 `TraceStore` 接口。接收器接受 schema 版本 1、2 和 3。版本 3 增加了原始 Provider 请求/响应负载以及分离的规范化 SDK 视图；较旧的本地 Trace 保持可读。

构建并运行独立服务器：

```bash
npm run lint
npm run build
npm run start
```

如果保留本地存储，请将 `AGENT_OBSERVABILITY_DATA_DIR` 挂载到持久卷上。标准的 `next start` 服务器适用于单节点 MVP；容器或进程管理器应该负责其生命周期。

## 隐私和运行行为

`AgentObservability.platform(...)` 便捷方法会捕获受限制的提示、模型响应、工具参数、工具结果和最终答案，这样节点检查器开箱即用。基于 Builder 的配置可以使用 `.captureContent(false)` 禁用此功能。Trace 内容、名称、错误、元数据和资源属性可能都是敏感的。保护数据目录，在 localhost 之外使用 HTTPS，轮换摄取密钥，并应用适合应用程序的保留策略。

对于捆绑的 OpenAI 兼容、OpenAI Responses 和 Anthropic HTTP 模型，schema 版本 3 会在 **Provider request** 和 **Provider response** 下显示实际的 Provider JSON 字段。规范的 Core 表示仍然可以在 **SDK input** 和 **SDK output** 下获得。流式响应显示捕获的具有规范化 LF 换行符的 SSE 事件块。Provider 的请求标头被排除在外，并且捕获的端点 URL 会省略查询字符串和片段。

平台的投递永远不会阻塞 Agent Loop 的网络 I/O。当满时，有界队列会丢弃最新的 Trace，并在 `PlatformTraceExporter` 上暴露计数器。请监控失败和丢弃的计数；可观测性绝对不能悄悄变成 Agent 本身的可靠性依赖。
