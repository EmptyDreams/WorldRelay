package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

func fixtureID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func isolatedServer(t *testing.T) (string, int, int) {
	t.Helper()
	jar := os.Getenv("WORLDRELAY_TEST_JAR")
	if jar == "" {
		t.Skip("设置 WORLDRELAY_TEST_JAR 可运行真实服务端集成测试")
	}
	java := os.Getenv("WORLDRELAY_TEST_JAVA")
	if java == "" {
		java = "java"
	}
	jar, err := filepath.Abs(jar)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	var public bytes.Buffer
	_ = binary.Write(&public, binary.BigEndian, uint32(len("ssh-ed25519")))
	public.WriteString("ssh-ed25519")
	_ = binary.Write(&public, binary.BigEndian, uint32(32))
	public.Write(make([]byte, 32))
	if err := os.WriteFile(filepath.Join(dir, "host.pub"), []byte("ssh-ed25519 "+base64.StdEncoding.EncodeToString(public.Bytes())+"\n"), 0600); err != nil {
		t.Fatal(err)
	}
	config := "http.bind-address=127.0.0.1\nhttp.port=0\nregistration.port=0\nhttp.shutdown-grace-seconds=0\nauth.users-file=data/users.json\nworlds.directory=data/worlds\ntunnel.mode=wss\ntunnel.bind-address=127.0.0.1\ntunnel.port=0\nssh.transfer-user=fixture\nssh.host-public-key-file=host.pub\nssh.authorized-keys-file=data/ssh/authorized_keys\nssh.transfer-directory=data/transfers\nssh.rrsync-command=/usr/bin/false\nretention.minimum-free-bytes=0\n"
	if err := os.WriteFile(filepath.Join(dir, "server.properties"), []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	log, err := os.Create(filepath.Join(dir, "server.log"))
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(java, "-Xmx256m", "-jar", jar, "--config", filepath.Join(dir, "server.properties"))
	cmd.Dir = dir
	cmd.Stdout = log
	cmd.Stderr = log
	if err := cmd.Start(); err != nil {
		log.Close()
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	t.Cleanup(func() {
		_ = cmd.Process.Signal(syscall.SIGTERM)
		select {
		case <-done:
		case <-time.After(15 * time.Second):
			_ = cmd.Process.Kill()
			<-done
		}
		log.Close()
	})
	pattern := regexp.MustCompile(`WorldRelay (HTTP|registration) listening on /127\.0\.0\.1:(\d+)`)
	deadline := time.Now().Add(45 * time.Second)
	for time.Now().Before(deadline) {
		data, _ := os.ReadFile(log.Name())
		matches := pattern.FindAllStringSubmatch(string(data), -1)
		if len(matches) >= 2 {
			ports := map[string]int{}
			for _, match := range matches {
				ports[match[1]], _ = strconv.Atoi(match[2])
			}
			return dir, ports["HTTP"], ports["registration"]
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("独立服务未在 45 秒内启动")
	return "", 0, 0
}

func business(t *testing.T, c *client, port int, token, path string, body object) response {
	t.Helper()
	data, _ := json.Marshal(body)
	req, err := http.NewRequest(http.MethodPost, fmt.Sprintf("http://127.0.0.1:%d/api/v1/worlds/%s", port, path), bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	req.SetBasicAuth("ConsoleTest", token)
	req.Header.Set("Content-Type", "application/json")
	r, err := c.http.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	data, err = io.ReadAll(r.Body)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := decodeObject(data)
	if err != nil {
		t.Fatal(err)
	}
	if r.StatusCode != 200 && r.StatusCode != 201 && r.StatusCode != 202 {
		t.Fatalf("%s: HTTP %d, code=%s", path, r.StatusCode, text(parsed, "code"))
	}
	return response{r.StatusCode, parsed}
}

func fixtureCommit(t *testing.T, c *client, root string, port int, token string, owner object, base any, revision int) {
	t.Helper()
	copyBody := func(extra object) object {
		body := object{}
		for k, v := range owner {
			body[k] = v
		}
		for k, v := range extra {
			body[k] = v
		}
		return body
	}
	business(t, c, port, token, "stop", copyBody(object{"stage": "BEGIN"}))
	business(t, c, port, token, "stop", copyBody(object{"stage": "UPLOAD_COMPLETE", "baseVersion": base}))
	request := copyBody(object{"baseVersion": base, "uploadId": fixtureID()})
	poll := func(path string) {
		t.Helper()
		deadline := time.Now().Add(30 * time.Second)
		for {
			r := business(t, c, port, token, path, request)
			if r.status != 202 {
				return
			}
			if time.Now().After(deadline) {
				t.Fatal("业务后台任务超时")
			}
			time.Sleep(20 * time.Millisecond)
		}
	}
	poll("upload")
	hash := sha256.Sum256([]byte("ConsoleTest"))
	payload := filepath.Join(root, "data", "transfers", ".sessions", hex.EncodeToString(hash[:]), text(owner, "worldId"), text(owner, "epoch"), "upload-"+text(request, "uploadId"), "payload")
	if !strings.HasPrefix(payload, root+string(os.PathSeparator)) {
		t.Fatal("测试目录越界")
	}
	data := []byte(fmt.Sprintf("synthetic-level-%d", revision))
	if err := os.WriteFile(filepath.Join(payload, "world", "level.dat"), data, 0600); err != nil {
		t.Fatal(err)
	}
	var manifest bytes.Buffer
	_ = binary.Write(&manifest, binary.BigEndian, uint32(0x57525332))
	_ = binary.Write(&manifest, binary.BigEndian, uint32(1))
	writeUTF := func(s string) { _ = binary.Write(&manifest, binary.BigEndian, uint16(len(s))); manifest.WriteString(s) }
	writeUTF("level.dat")
	manifest.WriteByte(0)
	_ = binary.Write(&manifest, binary.BigEndian, uint64(len(data)))
	digest := sha256.Sum256(data)
	writeUTF(hex.EncodeToString(digest[:]))
	if err := os.WriteFile(filepath.Join(payload, "manifest.bin"), manifest.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	digest = sha256.Sum256(manifest.Bytes())
	request["manifestHash"] = hex.EncodeToString(digest[:])
	poll("commit")
}

func finishOperation(t *testing.T, m *model) {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for m.pending != nil && m.notice == "" {
		if time.Now().After(deadline) {
			t.Fatal("管理后台任务超时")
		}
		time.Sleep(20 * time.Millisecond)
		complete(t, m, choosePageAction(t, m, "retry"))
	}
	if m.notice != "" || m.pending != nil || m.screen != resultScreen {
		t.Fatalf("管理操作未完成：%s", m.notice)
	}
}

// 在随机端口和独立数据目录启动真实 Java 服务，驱动终端完成账户、两次提交、恢复、历史、放弃与删除闭环。
func TestRealServerManagement(t *testing.T) {
	root, api, admin := isolatedServer(t)
	c := newClient(admin, 5*time.Second)
	defer c.http.CloseIdleConnections()
	m := newModel(c, journal{filepath.Join(root, "console-state")}, 0)
	complete(t, m, m.load())
	if text(m.status, "version") == "—" {
		t.Fatal("未读取真实服务概览")
	}
	goToPage(t, m, usersScreen)
	choosePageAction(t, m, "create-user")
	complete(t, m, submitValue(t, m, "ConsoleTest"))
	token := text(m.result, "token")
	if token == "—" {
		t.Fatal("注册未返回 Token")
	}
	goToPage(t, m, worldsScreen)
	chooseWorldAction(t, m, "create")
	complete(t, m, submitValue(t, m, "终端测试世界"))
	world := text(m.result, "worldId")
	goToPage(t, m, worldsScreen)
	chooseWorldAction(t, m, "rename")
	complete(t, m, submitValue(t, m, "改名后的世界"))
	if m.screen != worldsScreen || m.worldPane {
		t.Fatal("改名后没有直接返回世界列表")
	}
	goToPage(t, m, worldsScreen)
	if text(m.world, "name") != "改名后的世界" {
		t.Fatal("真实改名未生效")
	}
	// 业务获取响应不回传 deviceId，使用请求中同一设备标识构造后续命令。
	acquireOwner := func() object {
		device := fixtureID()
		r := business(t, c, api, token, "acquire", object{"worldId": world, "deviceId": device, "requestId": fixtureID()})
		return object{"worldId": world, "deviceId": device, "sessionId": r.body["sessionId"], "epoch": r.body["epoch"]}
	}
	fixtureCommit(t, c, root, api, token, acquireOwner(), nil, 1)
	fixtureCommit(t, c, root, api, token, acquireOwner(), json.Number("1"), 2)
	complete(t, m, chooseWorldAction(t, m, "refresh"))
	if len(m.backups) != 2 {
		t.Fatal("真实备份数量错误")
	}
	key(m, " ")
	key(m, "down")
	complete(t, m, chooseWorldAction(t, m, "restore"))
	complete(t, m, submitValue(t, m, "改名后的世界"))
	finishOperation(t, m)
	if integer(m.result, "version") != 3 {
		t.Fatal("恢复未发布新版本")
	}
	goToPage(t, m, worldsScreen)
	if integer(obj(m.detail, "storage"), "currentBytes") <= 0 || text(m.world, "previousOwner") != "ConsoleTest" {
		t.Fatal("真实世界存储或上一持有者缺失")
	}
	if integer(m.backups[0], "version") != 3 || integer(m.backups[0], "restoredFromVersion") != 1 || !strings.Contains(m.worldWorkspace(), "当前 · 恢复自 #1") {
		t.Fatal("当前快照没有区分新版本和恢复来源")
	}
	history, historyError := c.call(context.Background(), "worlds/history", object{"worldId": world, "limit": 50})
	if historyError != nil {
		t.Fatal(historyError)
	}
	found := false
	for _, e := range rows(history.body, "events") {
		if text(e, "type") == "RESTORE" {
			found = true
		}
	}
	if !found {
		t.Fatal("真实历史缺少恢复记录")
	}
	owner := acquireOwner()
	openUsersFromWorlds(t, m)
	choosePageAction(t, m, "toggle-user")
	complete(t, m, submitValue(t, m, "ConsoleTest"))
	goToPage(t, m, worldsScreen)
	if text(m.world, "epoch") != text(owner, "epoch") || obj(m.world, "owner") == nil {
		t.Fatal("禁用意外释放所有权")
	}
	openUsersFromWorlds(t, m)
	choosePageAction(t, m, "toggle-user")
	complete(t, m, submitValue(t, m, "ConsoleTest"))
	goToPage(t, m, worldsScreen)
	chooseWorldAction(t, m, "abandon")
	complete(t, m, submitValue(t, m, "独立测试结束"))
	complete(t, m, submitValue(t, m, "改名后的世界"))
	finishOperation(t, m)
	goToPage(t, m, worldsScreen)
	key(m, "end")
	complete(t, m, chooseWorldAction(t, m, "backup-delete"))
	complete(t, m, submitValue(t, m, "改名后的世界"))
	finishOperation(t, m)
	goToPage(t, m, worldsScreen)
	if len(m.backups) != 2 {
		t.Fatal("真实历史备份删除未生效")
	}
	complete(t, m, chooseWorldAction(t, m, "delete"))
	complete(t, m, submitValue(t, m, "改名后的世界"))
	finishOperation(t, m)
	if !flagValue(m.result, "deleted") {
		t.Fatal("真实删除未成功")
	}
	r, err := c.call(context.Background(), "worlds", nil)
	if err != nil || len(rows(r.body, "worlds")) != 0 {
		t.Fatal("删除后的世界仍在列表")
	}
}
