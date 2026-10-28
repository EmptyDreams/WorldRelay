# WorldRelay Server

轻量中心协调服务的 Java 21 模块，与 Minecraft 模组没有代码依赖。

当前已实现 HTTP 基础骨架：可配置监听、路由注册、虚拟线程请求执行、并发限制、请求 ID、
统一错误、请求日志和关闭流程，并提供字符串式 JSON 读写工具。
生产入口已注册共享世界列表假数据接口；鉴权、真实世界协调和存档同步尚未实现。

## 启动

在仓库根目录执行（需要 JDK 21）：

```powershell
.\gradlew.bat :server:run
```

默认监听 `127.0.0.1:8080`。显式指定 UTF-8 `.properties` 配置文件：

```powershell
.\gradlew.bat :server:run --args="--config D:/WorldRelay/server.properties"
```

配置示例：

```properties
http.bind-address=127.0.0.1
http.port=8080
http.max-concurrent-requests=32
http.shutdown-grace-seconds=5
```

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `http.bind-address` | `127.0.0.1` | 监听地址；需要外部访问时可显式配置为 `0.0.0.0` |
| `http.port` | `8080` | 端口范围 0–65535；0 自动选择可用端口，启动日志输出实际地址 |
| `http.max-concurrent-requests` | `32` | 同时处理的请求上限，必须为正整数；超限返回 503 |
| `http.shutdown-grace-seconds` | `5` | 关闭时等待正在处理请求的宽限期，必须为非负整数 |

不指定 `--config` 时直接使用默认配置，不自动查找或创建配置文件。指定文件不存在、
配置项未知或值不合法时启动失败并返回非零退出码。公网入口、HTTPS 和虚拟网络由部署环境管理。

## 共享世界列表（假数据）

`GET /api/v1/worlds` 固定返回四条共享世界摘要，无需请求体，不提供分页或筛选参数。
默认地址为 `http://127.0.0.1:8080/api/v1/worlds`：

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/worlds" -Method Get
```

成功返回 200，内容类型为 `application/json; charset=utf-8`。
以下响应结构示例节选了其中一条记录，实际固定返回下表中的四条：

```json
{
  "mock": true,
  "worlds": [
    {
      "worldId": "demo-survival",
      "name": "我们的生存世界",
      "status": "RUNNING",
      "hostName": "小明",
      "playerCount": 2,
      "lastCommittedAt": "2026-10-28T10:40:00Z"
    }
  ]
}
```

| worldId | 名称 | 状态 | 房主 | 在线人数 | 最后提交时间 |
|---|---|---|---|---|---|
| `demo-survival` | 我们的生存世界 | `RUNNING` | 小明 | 2 | `2026-10-28T10:40:00Z` |
| `demo-building` | 建筑练习场 | `FREE` | null | 0 | `2026-10-27T13:16:00Z` |
| `demo-machinery` | 机械与铁路 | `RECOVERY_REQUIRED` | 小雨 | null | `2026-10-28T09:22:00Z` |
| `demo-adventure` | 朋友的冒险 | `COMMITTING` | 阿木 | null | `2026-10-28T08:05:00Z` |

`mock: true` 表示固定示例，连续请求的数据及顺序保持不变。`hostName` 无房主时为 JSON null；
`playerCount` 无法确定时为 JSON null，无人托管时为 0；`lastCommittedAt` 是最后成功提交时间，
采用固定 UTC 时间字符串。每个响应仍通过 `X-Request-Id` 返回独立的请求 ID。

当前接口不读取真实存档，不进行鉴权或所有权操作，也未接入客户端页面。
状态仅供展示，不代表已有可连接的房主。POST 等未注册的方法返回 405 和 `Allow: GET`；
尾斜杠、子路径及未知路径仍返回 404。

## 路由与响应

通过 `HttpRouter.register(method, path, HttpHandler)` 显式注册处理器，然后将路由器传给
`WorldRelayHttpServer`。路由注册必须在启动前完成，同一方法和路径不能重复注册。
目前仅支持大小写敏感的方法和原始路径精确匹配；查询参数不参与匹配，尾斜杠、编码后的路径
和子路径不会自动合并。不自动补充 HEAD 或 OPTIONS 路由。

处理器使用 JDK `HttpExchange`，可调用 `HttpResponses.text`、`bytes`、`noContent` 或 `error`。
响应辅助方法处理 UTF-8、固定长度响应以及 HEAD、204、205、304 无响应体场景。
处理器应在返回前完成响应写入；框架负责最终关闭 exchange，不支持处理器返回后异步写出。

每个请求由服务端生成独立请求 ID，并通过 `X-Request-Id` 响应头返回，不使用客户端传入的 ID。
框架错误格式为：

```json
{"code":"NOT_FOUND","message":"Route not found","requestId":"服务端生成的 UUID"}
```

| 状态码 | 错误码 | 场景 |
|---|---|---|
| 404 | `NOT_FOUND` | 路径未注册 |
| 405 | `METHOD_NOT_ALLOWED` | 路径存在但方法未注册，同时返回 `Allow` 头 |
| 503 | `SERVER_BUSY` | 同时处理的请求数达到上限 |
| 500 | `INTERNAL_ERROR` | 处理器异常或未发送响应便返回 |

错误响应不包含异常详情。响应已经开始发送时发生异常，只记录异常并关闭连接，
不追加第二份错误响应。JDK 无法接受的协议错误由底层 HTTP 实现处理。
请求日志使用 JDK 日志，记录方法、原始路径、状态、耗时及请求 ID，不记录查询参数、凭证或请求体。

## 生命周期与验证

`WorldRelayHttpServer` 只允许启动一次，`close()` 可重复调用。正常进程退出由 shutdown hook
停止监听；宽限期结束后关闭未完成的连接并中断剩余请求任务。并发限制针对处理器执行，
不代表 TCP 连接总数限制。请求体限制、读写超时和鉴权将在后续接口实现时补充。

```powershell
.\gradlew.bat :server:build
```

HTTP 测试通过 JDK `HttpClient` 访问环回地址的随机端口，验证生产路由及测试专用处理器。
运行时零第三方依赖，JUnit 仅用于测试。未引入反射扫描、动态加载或 GraalVM 构建任务；
Native Image 兼容性仍需原生构建验证。
