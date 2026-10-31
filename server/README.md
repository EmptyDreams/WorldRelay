# WorldRelay Server

轻量中心协调服务的 Java 21 模块，与 Minecraft 模组没有代码依赖。

当前已实现 HTTP 基础骨架：可配置监听、路由注册、虚拟线程请求执行、并发限制、请求 ID、
统一错误、请求日志和关闭流程，并提供字符串式 JSON 读写工具。
生产入口已注册共享世界列表假数据接口，并实现本地注册、用户凭据持久化和统一 Basic 鉴权；
真实世界协调和存档同步尚未实现。

## 启动

在仓库根目录执行（需要 JDK 21）：

```powershell
.\gradlew.bat :server:run
```

业务接口默认监听 `127.0.0.1:8080`，注册接口固定绑定 `127.0.0.1`，默认端口为 `8081`。
显式指定 UTF-8 `.properties` 配置文件：

```powershell
.\gradlew.bat :server:run --args="--config D:/WorldRelay/server.properties"
```

配置示例：

```properties
http.bind-address=127.0.0.1
http.port=8080
http.max-concurrent-requests=32
http.shutdown-grace-seconds=5
registration.port=8081
auth.users-file=data/users.json
```

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `http.bind-address` | `127.0.0.1` | 监听地址；需要外部访问时可显式配置为 `0.0.0.0` |
| `http.port` | `8080` | 端口范围 0–65535；0 自动选择可用端口，启动日志输出实际地址 |
| `http.max-concurrent-requests` | `32` | 同时处理的请求上限，必须为正整数；超限返回 503 |
| `http.shutdown-grace-seconds` | `5` | 关闭时等待正在处理请求的宽限期，必须为非负整数 |
| `registration.port` | `8081` | 注册专用端口，范围 0–65535；固定绑定 `127.0.0.1`，不能与业务端口使用相同的非零端口 |
| `auth.users-file` | `data/users.json` | 用户名与密钥 SHA-256 摘要的 UTF-8 JSON 文件；相对路径按进程工作目录解析 |

不指定 `--config` 时直接使用默认配置，不自动查找或创建配置文件。指定文件不存在、
配置项未知或值不合法时启动失败并返回非零退出码。两端口都配置为 0 时分别分配端口，
启动日志输出实际监听地址。公网入口、HTTPS 和虚拟网络由部署环境管理；Basic 的 Base64
编码不提供加密，业务凭据应通过 HTTPS 或受保护网络传输。不要将注册端口反向代理到外部网络。

## 注册与鉴权

在服务器本机通过 `POST http://127.0.0.1:8081/api/v1/register` 注册，无需已有凭据。
请求必须为 UTF-8 `application/json`，只包含字符串字段 `username`，请求体上限为 4096 字节：

```powershell
$registration = Invoke-RestMethod -Uri "http://127.0.0.1:8081/api/v1/register" -Method Post `
    -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes('{"username":"小明"}'))
$registration
```

成功返回 `201`，例如 `{"username":"小明","key":"<随机密钥>"}`，并设置 `Cache-Control: no-store`。
密钥由 32 字节安全随机数生成，使用无填充 Base64URL 编码，长度为 43 个字符。
原密钥仅在本次注册响应中返回，不写入用户文件或日志，需妥善保存。
用户名区分大小写，允许 1–64 个 Unicode 码点，不允许首尾空白、全空白名称、控制字符或冒号。
相同用户名再次注册返回 `409 USERNAME_EXISTS`，原密钥保持有效；目前没有找回或重置接口。

注册监听器只提供注册路由，同时检查连接的本地地址和真实对端地址均为 `127.0.0.1`；
IPv6 `::1`、其他环回地址及远程地址均不能注册。`Forwarded` 和 `X-Forwarded-For` 不影响判断。
注册路由不在业务端口提供，世界列表等业务路由也不在注册端口提供。

所有业务请求必须携带 `Authorization: Basic <Base64(UTF-8 用户名:密钥)>`：

```powershell
$credentials = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes($registration.username + ":" + $registration.key))
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/worlds" -Method Get `
    -Headers @{ Authorization = "Basic $credentials" }
```

缺失、格式错误、重复认证头、未知用户及错误密钥均返回相同的 `401 UNAUTHORIZED`，附带
`WWW-Authenticate: Basic realm="WorldRelay", charset="UTF-8"`。业务监听器在路由之前统一鉴权，
未知路径、未注册方法和以后新增的路由也必须先认证；认证后才按原路由规则返回 404 或 405。

用户文件首次不存在时按空用户集合启动；首次成功注册才创建文件。已存在但无法读取或格式损坏
会阻止启动，不会自动清空。注册在文件锁保护下串行执行，先写同目录临时文件、刷盘并原子替换，
成功后才使凭据生效。不支持原子替换或写盘失败时返回 500，可重试；不会降级为普通覆盖写。
旁边的 `.lock` 文件由服务持锁，多个实例不能同时使用同一用户文件；退出时释放锁，但保留锁文件。

## 共享世界列表（假数据）

`GET /api/v1/worlds` 固定返回四条共享世界摘要，无需请求体，不提供分页或筛选参数。
默认地址为 `http://127.0.0.1:8080/api/v1/worlds`：

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/worlds" -Method Get `
    -Headers @{ Authorization = "Basic $credentials" }
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

当前接口需要上述鉴权，不读取真实存档，不进行所有权操作，也未接入客户端页面。
状态仅供展示，不代表已有可连接的房主。认证后 POST 等未注册的方法返回 405 和 `Allow: GET`；
尾斜杠、子路径及未知路径仍返回 404。

## 路由与响应

通过 `HttpRouter.register(method, path, HttpHandler)` 显式注册处理器，然后将路由器传给
`WorldRelayHttpServer`。生产环境由 `WorldRelayService` 在整个业务路由器外装配鉴权，处理器可通过
`exchange.getPrincipal().getUsername()` 或只读属性 `worldrelay.username` 获取当前请求用户名。
身份保存在每个请求独立的包装器中，避免并发请求共享 context 属性导致身份串用。
路由注册必须在启动前完成，同一方法和路径不能重复注册。
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
| 400 | `INVALID_REGISTRATION` | 注册 JSON 或用户名无效 |
| 401 | `UNAUTHORIZED` | 业务请求凭据缺失或无效 |
| 403 | `LOCAL_ACCESS_REQUIRED` | 注册连接的真实地址不是 `127.0.0.1` |
| 404 | `NOT_FOUND` | 路径未注册 |
| 405 | `METHOD_NOT_ALLOWED` | 路径存在但方法未注册，同时返回 `Allow` 头 |
| 409 | `USERNAME_EXISTS` | 用户名已注册，保留原密钥 |
| 413 | `PAYLOAD_TOO_LARGE` | 注册请求体超过 4096 字节 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 注册请求未使用 `application/json` |
| 503 | `SERVER_BUSY` | 同时处理的请求数达到上限 |
| 500 | `INTERNAL_ERROR` | 处理器异常或未发送响应便返回 |

错误响应不包含异常详情。响应已经开始发送时发生异常，只记录异常并关闭连接，
不追加第二份错误响应。JDK 无法接受的协议错误由底层 HTTP 实现处理。
请求日志使用 JDK 日志，记录方法、原始路径、状态、耗时及请求 ID，不记录查询参数、凭证或请求体。

## 生命周期与验证

`WorldRelayService` 和 `WorldRelayHttpServer` 只允许启动一次，`close()` 可重复调用。
任一监听器启动失败时关闭已启动的监听器并释放用户文件锁。正常进程退出由 shutdown hook
停止两个监听器；各监听器的宽限期结束后关闭未完成的连接并中断剩余请求任务。
并发限制分别应用于两个监听器的处理器执行，不代表 TCP 连接总数限制。
当前仅注册接口有请求体上限，通用读写超时仍待补充。

```powershell
.\gradlew.bat :server:build
```

HTTP 测试通过 JDK `HttpClient` 访问环回地址的随机端口，验证注册、鉴权、端口隔离、并发身份、
并发重复注册、重启持久化及部分启动失败清理；存储测试覆盖原子发布失败、损坏文件和文件锁。
真实 socket 地址限制另使用测试 exchange 验证非本机来源及伪造转发头。
运行时零第三方依赖，JUnit 仅用于测试。未引入反射扫描、动态加载或 GraalVM 构建任务；
Native Image 兼容性仍需原生构建验证。