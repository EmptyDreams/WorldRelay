# WorldRelay 管理终端

**连上服务器的 SSH，就能查看服务状态、管理世界和玩家。**

首页展示联机情况、CPU、内存、磁盘、网络和后台任务。管理世界、恢复备份、处理未上传进度都可以在终端中完成，无需浏览器或手写接口请求。

程序支持 Linux x64 和 ARM64。

## 开始使用

使用[服务端部署命令](../server/README.md)时，管理终端会一起下载到服务程序旁边。单独安装时：

1. 从服务端同版本的 Release 下载对应架构的 `worldrelay-linux-*.tar.gz` 或 `worldrelay-jar-linux-*.tar.gz`，两类包都包含管理终端；也可从 **Actions → Admin Console** 获取单独构建产物。
2. 将压缩包中的 `worldrelay-admin` 放到服务程序旁边。
3. 通过 SSH 登录服务端所在机器，运行：

   ```bash
   chmod +x ./worldrelay-admin
   ./worldrelay-admin --port 8081
   ```

端口填写服务端的 `registration.port`，默认 `8081`。管理终端只连接本机服务，请在同一台机器运行。使用 UTF-8 终端，建议窗口至少为 80 列 × 24 行；远程直接执行时使用 `ssh -t`。

独立账户部署时，使用 `sudo -H -u worldrelay /home/worldrelay/worldrelay-admin --port 8081`，账户名和路径按实际部署替换。

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

首页和用户列表默认每 5 秒刷新；世界列表需要选择「刷新」手动更新。