# WorldRelay 管理终端

**连上服务器的 SSH，就能查看服务状态、管理世界和玩家。**

首页展示联机情况、CPU、内存、磁盘、网络和后台任务。管理世界、恢复备份、处理未上传进度都可以在终端中完成，无需浏览器或手写接口请求。

程序支持 Linux x64 和 ARM64。

## 开始使用

使用[服务端部署命令](../server/README.md)时，管理终端会一起下载到服务程序旁边。单独安装时：

1. 从服务端同版本的 Release 下载对应架构的 `worldrelay-linux-*.tar.gz` 或 `worldrelay-jar-linux-*.tar.gz`，两类包都包含管理终端；也可从 **Actions → Admin Console** 获取单独构建产物。
2. 将压缩包中的 `worldrelay-admin` 与 `worldrelay-server`（或 `worldrelay-server.jar`）、`server.properties` 放在同一目录。
3. 通过 SSH 登录服务端所在机器，运行：

   ```bash
   chmod +x ./worldrelay-admin
   ./worldrelay-admin
   ```

管理终端自动读取自身所在目录的 `server.properties`，使用其中的 `registration.port` 连接本机服务，无需传入端口。未配置该项时使用服务端默认值 `8081`。从其他目录或通过符号链接启动，也会读取实际程序目录中的配置。

使用 UTF-8 终端，建议窗口至少为 80 列 × 24 行；远程直接执行时使用 `ssh -t`。

独立账户部署时，使用 `sudo -H -u worldrelay /home/worldrelay/worldrelay-admin`，账户名和路径按实际部署替换。

## 怎么操作

| 操作 | 按键 |
| --- | --- |
| 切换底部按钮 | `←` / `→` 或 `A` / `D` |
| 执行选中按钮 | `Enter` |
| 选择列表条目 | `↑` / `↓` |
| 世界列表与详情之间切换 | 空格 |
| 滚动较长内容 | 将鼠标放在内容区，使用滚轮 |
| 返回上一级 | `Esc` |
| 退出管理终端 | `Ctrl+C` |

填写名称、原因或确认文字时，直接输入，按 `Enter` 提交、`Esc` 取消。此时方向键用于编辑文字，也支持终端粘贴。

创建账户或重置 Token 成功后会直接显示 Token。此页可用鼠标选中文字，再使用终端的复制功能；返回列表后恢复鼠标滚动。请及时保存 Token，离开结果页后会清除显示，管理终端不将其写入文件。

首页和用户列表默认每 5 秒刷新；世界列表需要选择「刷新」手动更新。
