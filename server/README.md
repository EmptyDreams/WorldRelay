# WorldRelay 服务端

全局 OP 名单由本机管理接口维护，持久保存在用户文件旁的 `<用户文件名>.operators.json`，独立于世界快照；最多 128 个账号。管理员通过 admin 用户管理页设置 1–4 级权限或撤销。共享世界客户端不读写本机 `ops.json`，启动前加载中心名单，运行中按心跳同步。正版与离线模式均使用实际登录的账号名匹配已注册账号，并以实际 Minecraft UUID 生成内存条目。

管理监听器提供 `GET /api/v1/operators`、`POST /api/v1/operators/set`（`username`、`level`、`expectedRevision`）和 `POST /api/v1/operators/remove`（`username`、`expectedRevision`）。版本冲突返回 409，不覆盖新配置。业务监听器仅提供只读同步 `POST /api/v1/worlds/operators`，需要鉴权和当前房主的 `worldId`、`deviceId`、`sessionId`、`epoch`，以及 `appliedRevision`（首次为 -1）。响应携带任期与配置版本；版本相同返回 `unchanged:true`，否则返回完整名单。客户端与服务端须一并更新，首次拉取失败时保留所有权并阻止开服。

**一台小服务器保存存档，朋友轮流当房主，同一个世界接着玩。**

服务端提供存档交接、历史备份和连接转发，无需安装 Minecraft 服务端。原生版无需 Java，JAR 版需要 Java 21+。

默认面向 5 人以内的朋友联机，实际可承载的联机规模还取决于房主的电脑和家庭上行带宽。

## 在线配置助手

如果不想研究 WorldReplay 的配置文件，或有些配置项看不明白，可以让 deepseek 来帮你填写配置项，注意对话中不要暴露你的私钥等隐私信息：

https://chat.deepseek.com/share/37grx0wur6c00l0ogc

## 安装

x64 服务程序不依赖系统 glibc；ARM64 服务程序需要 glibc 2.28+。两种架构都需要下文列出的 SSH、rsync 等工具。

以下命令面向 **Ubuntu 22.04+ / Debian 12+，x64 或 ARM64**，请在 Bash 中执行。填写开头的 `VERSION`（不带 `server-v`）和新的部署目录，`EDITION` 默认选原生版，改为 `jar` 则下载 JAR 版。两种部署方式选一种执行即可。

### 有 sudo：独立账户，禁用交互登录

使用独立账户，禁用密码登录、交互终端和转发，只接受 WorldRelay 签发的受限传输密钥。账户保留 `/bin/sh`，供 SSH 执行 rsync；直接使用 `nologin` 会使传输也无法运行。[OpenSSH 说明](https://man.openbsd.org/sshd_config#ForceCommand)

部署目录同时作为新账户的主目录，默认 `/home/worldrelay`。SSH 配置保存在部署目录并通过软链接接入系统，systemd 服务文件直接放在 `/etc/systemd/system/`。

请先自行准备 OpenSSH 服务端、systemd、`curl`、`rsync`、`rrsync`、`python3` 和 `nano`。命令会先检查环境，缺少工具时提示并退出。

整段按顺序执行，打开配置编辑器后按需修改，保存退出即启动服务，并启用开机启动：

```bash
(
set -euo pipefail
VERSION="x.y.z"                     # 改为实际 Release 版本号
EDITION="native"                    # native 或 jar；JAR 版先准备系统 Java 21+
SERVICE_USER="worldrelay"           # 新建的专用账户名
DEPLOY_DIR="/home/$SERVICE_USER"    # 新账户的主目录和部署目录
REPO="EmptyDreams/WorldRelay"

# 确认版本已填写，部署目录尚不存在。
[[ "$VERSION" != "x.y.z" ]] || { printf '请先填写 VERSION\n' >&2; exit 1; }
test ! -e "$DEPLOY_DIR"

# 根据 CPU 架构和版本类型选择发布包。
case "$(uname -m)" in
  x86_64) ARCH="x64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) printf '不支持的 CPU 架构\n' >&2; exit 1 ;;
esac
case "$EDITION" in
  native) ASSET="worldrelay-linux-$ARCH.tar.gz" ;;
  jar) ASSET="worldrelay-jar-linux-$ARCH.tar.gz" ;;
  *) printf 'EDITION 只能填写 native 或 jar\n' >&2; exit 1 ;;
esac
URL="https://github.com/$REPO/releases/download/server-v$VERSION"

# 检查部署所需工具；JAR 版还需 Java。
sudo sh -c '
for tool in curl tar sha256sum rsync rrsync python3 nano systemctl useradd usermod install chown tee ln; do
  command -v "$tool" >/dev/null || { printf "缺少工具：%s，请先自行安装\n" "$tool" >&2; exit 1; }
done
test -x /usr/sbin/sshd || { printf "缺少 OpenSSH 服务端，请先自行安装\n" >&2; exit 1; }
'
if [[ "$EDITION" == jar ]]; then
  sudo sh -c 'command -v java >/dev/null' || { printf '请先准备 Java 21+\n' >&2; exit 1; }
fi

# 创建专用账户和部署目录，禁用账户密码。
sudo useradd --system --user-group --home-dir "$DEPLOY_DIR" --shell /bin/sh "$SERVICE_USER"
sudo usermod --password '*' "$SERVICE_USER"
sudo install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 755 "$DEPLOY_DIR"

# 下载包含服务端和管理终端的发布包，校验后解压。
sudo curl -fL "$URL/$ASSET" -o "$DEPLOY_DIR/$ASSET"
sudo curl -fL "$URL/SHA256SUMS" -o "$DEPLOY_DIR/SHA256SUMS"
cd "$DEPLOY_DIR"
sudo sha256sum --check --ignore-missing SHA256SUMS
sudo tar --no-same-owner -xzf "$ASSET"

# 生成配置和运行目录，准备管理终端状态目录并设置权限。
sudo -H -u "$SERVICE_USER" ./worldrelay-server --config
sudo install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 700 "$DEPLOY_DIR/state"
sudo chown root:root "$DEPLOY_DIR"

# 限制专用账户的 SSH 登录方式，仅用于受限文件传输。
sudo tee "$DEPLOY_DIR/sshd-worldrelay.conf" >/dev/null <<EOF
Match User $SERVICE_USER
    AuthenticationMethods publickey
    PubkeyAuthentication yes
    AuthorizedKeysFile .ssh/authorized_keys
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    PermitTTY no
    DisableForwarding yes
    PermitUserRC no
Match all
EOF

# 接入并检查 SSH 配置，确认通过后重新加载。
sudo ln -s "$DEPLOY_DIR/sshd-worldrelay.conf" /etc/ssh/sshd_config.d/00-worldrelay.conf
sudo /usr/sbin/sshd -t
sudo systemctl enable --now ssh
sudo systemctl reload ssh

# 注册后台服务，指定运行账户、目录和异常重启方式。
sudo tee /etc/systemd/system/worldrelay.service >/dev/null <<EOF
[Unit]
Description=WorldRelay Server
After=network-online.target ssh.service
Wants=network-online.target

[Service]
User=$SERVICE_USER
WorkingDirectory=$DEPLOY_DIR
ExecStart="$DEPLOY_DIR/worldrelay-server"
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
UMask=0077

[Install]
WantedBy=multi-user.target
EOF
)
```

安装成功之后配置文件在 `$DEPLOY_DIR/server.properties` 目录下，编辑后重启服务器即可生效，常用管理命令：

```bash
# 重载 systemd 配置
sudo systemctl daemon-reload
# 设置开机自启
sudo systemctl enable worldrelay
# 禁用开机自启
sudo systemctl disable worldrelay
# 检查服务状态
sudo systemctl status worldrelay --no-pager
# 启动服务
sudo systemctl start worldrelay
# 停止服务
sudo systemctl stop worldrelay
# 重启服务
sudo systemctl restart worldrelay
```

管理终端使用 `sudo -H -u <username> /home/<username>/worldrelay-admin`，自动读取同目录的 `server.properties` 获取管理端口。账户名和路径按实际部署替换。

### 无 sudo：在当前账户目录部署

需要管理员已为当前账户启用 SSH 公钥登录，并预装 `curl`、`rsync`、`rrsync`。

```bash
(
set -euo pipefail
VERSION="x.y.z"                     # 改为实际 Release 版本号
EDITION="native"                    # native 或 jar；JAR 版先准备 Java 21+
DEPLOY_DIR="$HOME/worldrelay"       # 当前账户内的新部署目录
REPO="EmptyDreams/WorldRelay"

# 确认版本已填写，部署目录尚不存在。
[[ "$VERSION" != "x.y.z" ]] || { printf '请先填写 VERSION\n' >&2; exit 1; }
test ! -e "$DEPLOY_DIR"

# 检查所需工具，缺少时先自行安装。
for tool in curl tar sha256sum rsync rrsync python3; do
  command -v "$tool" >/dev/null || { printf '缺少工具：%s\n' "$tool" >&2; exit 1; }
done

# 根据 CPU 架构和版本类型选择发布包。
case "$(uname -m)" in
  x86_64) ARCH="x64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) printf '不支持的 CPU 架构\n' >&2; exit 1 ;;
esac
case "$EDITION" in
  native) ASSET="worldrelay-linux-$ARCH.tar.gz" ;;
  jar) ASSET="worldrelay-jar-linux-$ARCH.tar.gz" ;;
  *) printf 'EDITION 只能填写 native 或 jar\n' >&2; exit 1 ;;
esac

# JAR 版需先确认 Java 可用。
if [[ "$EDITION" == jar ]]; then
  command -v java >/dev/null || { printf '请先准备 Java 21+\n' >&2; exit 1; }
fi
URL="https://github.com/$REPO/releases/download/server-v$VERSION"

# 创建部署目录，新文件默认仅当前账户可访问。
umask 077
mkdir -p "$DEPLOY_DIR"
cd "$DEPLOY_DIR"

# 下载包含服务端和管理终端的发布包，校验后解压。
curl -fL "$URL/$ASSET" -o "$ASSET"
curl -fL "$URL/SHA256SUMS" -o SHA256SUMS
sha256sum --check --ignore-missing SHA256SUMS
tar -xzf "$ASSET"

# 生成完整配置和运行目录。
./worldrelay-server --config

# 自动填入当前账户能找到的 rrsync 路径。
sed -i "s|^ssh.rrsync-command=.*|ssh.rrsync-command=$(command -v rrsync)|" server.properties
)
```

安装成功后编辑部署目录中的 `server.properties` 并重启服务器即可让新配置生效，可以用 `nohup` 或其它方式启动服务器，例如：

```bash
# 后台启动服务，保存日志和进程号以便后续查看、停止。
nohup ./worldrelay-server >server.log 2>&1 &
printf '%s\n' "$!" >server.pid
```

管理终端运行 `./worldrelay-admin`，自动读取程序同目录的 `server.properties` 获取管理端口。
