# WorldRelay 服务端

**一台小服务器保存存档，朋友轮流当房主，同一个世界接着玩。**

服务端提供存档交接、历史备份和连接转发，无需安装 Minecraft 服务端或 Java。

x64 服务程序不依赖系统 glibc；ARM64 服务程序需要 glibc 2.28+。两种架构都需要下文列出的 SSH、rsync 等工具。

以下命令面向 **Ubuntu 22.04+ / Debian 12+，x64 或 ARM64**，请在 Bash 中执行。先把开头的 `VERSION` 改为包含 `--config` 初始化功能的已发布版本号（不带 `server-v`），部署目录填写新的绝对路径。两种方式都会从同一版本的 Release 下载服务端和管理终端，解压到同一个目录，选一种执行即可。

## 有 sudo：独立账户，禁用交互登录

使用独立账户，禁用密码登录、交互终端和转发，只接受 WorldRelay 签发的受限传输密钥。账户保留 `/bin/sh`，供 SSH 执行 rsync；直接使用 `nologin` 会使传输也无法运行。[OpenSSH 说明](https://man.openbsd.org/sshd_config#ForceCommand)

部署目录同时作为新账户的主目录，默认 `/home/worldrelay`。SSH 配置保存在部署目录并通过软链接接入系统，systemd 服务文件直接放在 `/etc/systemd/system/`。

请先自行准备 OpenSSH 服务端、systemd、`curl`、`rsync`、`rrsync`、`python3` 和 `nano`。命令会先检查环境，缺少工具时提示并退出。

整段按顺序执行，打开配置编辑器后按需修改，保存退出即启动服务，并启用开机启动：

```bash
(
set -euo pipefail
VERSION="x.y.z"                       # 改为实际 Release 版本号
SERVICE_USER="worldrelay"             # 新建的专用账户名
DEPLOY_DIR="/home/$SERVICE_USER"       # 新账户的主目录和部署目录
REPO="EmptyDreams/WorldRelay"

[[ "$VERSION" != "x.y.z" ]] || { printf '请先填写 VERSION\n' >&2; exit 1; }
test ! -e "$DEPLOY_DIR"
case "$(uname -m)" in
  x86_64) ARCH="x64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) printf '不支持的 CPU 架构\n' >&2; exit 1 ;;
esac
ASSET="worldrelay-server-linux-$ARCH.tar.gz"
ADMIN_ASSET="worldrelay-admin-linux-$ARCH.tar.gz"
URL="https://github.com/$REPO/releases/download/server-v$VERSION"

sudo sh -c '
for tool in curl tar sha256sum rsync rrsync python3 nano systemctl useradd usermod install chown tee ln; do
  command -v "$tool" >/dev/null || { printf "缺少工具：%s，请先自行安装\n" "$tool" >&2; exit 1; }
done
test -x /usr/sbin/sshd || { printf "缺少 OpenSSH 服务端，请先自行安装\n" >&2; exit 1; }
'
sudo useradd --system --user-group --home-dir "$DEPLOY_DIR" --shell /bin/sh "$SERVICE_USER"
sudo usermod --password '*' "$SERVICE_USER"
sudo install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 755 "$DEPLOY_DIR"

sudo curl -fL "$URL/$ASSET" -o "$DEPLOY_DIR/$ASSET"
sudo curl -fL "$URL/$ASSET.sha256" -o "$DEPLOY_DIR/$ASSET.sha256"
sudo curl -fL "$URL/$ADMIN_ASSET" -o "$DEPLOY_DIR/$ADMIN_ASSET"
sudo curl -fL "$URL/SHA256SUMS" -o "$DEPLOY_DIR/SHA256SUMS"
cd "$DEPLOY_DIR"
sudo sha256sum -c "$ASSET.sha256"
sudo sha256sum --check --ignore-missing SHA256SUMS
sudo tar --no-same-owner -xzf "$ASSET"
sudo tar --no-same-owner -xzf "$ADMIN_ASSET" worldrelay-admin
sudo -H -u "$SERVICE_USER" ./worldrelay-server --config
sudo install -d -o "$SERVICE_USER" -g "$SERVICE_USER" -m 700 "$DEPLOY_DIR/state"
sudo chown root:root "$DEPLOY_DIR"

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
sudo ln -s "$DEPLOY_DIR/sshd-worldrelay.conf" /etc/ssh/sshd_config.d/00-worldrelay.conf
sudo /usr/sbin/sshd -t
sudo systemctl enable --now ssh
sudo systemctl reload ssh

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

# 按配置文件中的中文注释修改，至少确认 SSH 公网地址、端口及 rrsync 路径。
sudo nano "$DEPLOY_DIR/server.properties"
sudo systemctl daemon-reload
sudo systemctl enable --now worldrelay
sudo systemctl status worldrelay --no-pager
)
```

之后修改配置使用 `sudo nano /home/worldrelay/server.properties`，重启使用 `sudo systemctl restart worldrelay`，查看日志使用 `sudo journalctl -u worldrelay -f`。自定义目录时替换上述路径。

管理终端使用 `sudo -H -u worldrelay /home/worldrelay/worldrelay-admin --port 8081`，账户名、路径和管理端口按实际配置替换。

## 无 sudo：在当前账户目录部署

需要管理员已为当前账户启用 SSH 公钥登录，并预装 `curl`、`rsync`、`rrsync`、`python3`。此方式使用 `nohup` 后台运行，机器重启后需要重新启动程序。

```bash
(
set -euo pipefail
VERSION="x.y.z"                       # 改为实际 Release 版本号
DEPLOY_DIR="$HOME/worldrelay"          # 当前账户内的新部署目录
REPO="EmptyDreams/WorldRelay"

[[ "$VERSION" != "x.y.z" ]] || { printf '请先填写 VERSION\n' >&2; exit 1; }
test ! -e "$DEPLOY_DIR"
for tool in curl tar sha256sum rsync rrsync python3; do
  command -v "$tool" >/dev/null || { printf '缺少工具：%s\n' "$tool" >&2; exit 1; }
done
case "$(uname -m)" in
  x86_64) ARCH="x64" ;;
  aarch64|arm64) ARCH="arm64" ;;
  *) printf '不支持的 CPU 架构\n' >&2; exit 1 ;;
esac
ASSET="worldrelay-server-linux-$ARCH.tar.gz"
ADMIN_ASSET="worldrelay-admin-linux-$ARCH.tar.gz"
URL="https://github.com/$REPO/releases/download/server-v$VERSION"

umask 077
mkdir -p "$DEPLOY_DIR"
cd "$DEPLOY_DIR"
curl -fL "$URL/$ASSET" -o "$ASSET"
curl -fL "$URL/$ASSET.sha256" -o "$ASSET.sha256"
curl -fL "$URL/$ADMIN_ASSET" -o "$ADMIN_ASSET"
curl -fL "$URL/SHA256SUMS" -o SHA256SUMS
sha256sum -c "$ASSET.sha256"
sha256sum --check --ignore-missing SHA256SUMS
tar -xzf "$ASSET"
tar -xzf "$ADMIN_ASSET" worldrelay-admin
./worldrelay-server --config
# 自动填入当前账户能找到的 rrsync 路径。
sed -i "s|^ssh.rrsync-command=.*|ssh.rrsync-command=$(command -v rrsync)|" server.properties

# 打开编辑器，按中文注释修改配置；保存退出后启动。
"${EDITOR:-vi}" server.properties
nohup ./worldrelay-server >server.log 2>&1 &
printf '%s\n' "$!" >server.pid
)
```

之后在部署目录中修改 `server.properties`；停止使用 `kill "$(cat server.pid)"`，等待退出后再启动，启动命令为 `nohup ./worldrelay-server >server.log 2>&1 & echo $! >server.pid`，查看日志使用 `tail -f server.log`。

管理终端在同一目录中运行 `./worldrelay-admin --port 8081`，管理端口按实际配置替换。