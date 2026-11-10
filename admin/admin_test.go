package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/x/ansi"
)

type recorded struct {
	path string
	body object
}
type fixture struct {
	mu       sync.Mutex
	requests []recorded
	handler  func(string, object) (int, object)
}

func testClient(t *testing.T, handler func(string, object) (int, object)) (*client, *fixture) {
	t.Helper()
	f := &fixture{handler: handler}
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body object
		if r.Method == http.MethodPost {
			if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
				t.Error("POST 缺少 JSON 类型")
			}
			data, _ := io.ReadAll(r.Body)
			var err error
			body, err = decodeObject(data)
			if err != nil {
				t.Error(err)
			}
		}
		path := strings.TrimPrefix(r.URL.Path, "/api/v1/")
		f.mu.Lock()
		f.requests = append(f.requests, recorded{path, body})
		f.mu.Unlock()
		status, result := handler(path, body)
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Request-Id", "test-request")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(result)
	}))
	t.Cleanup(s.Close)
	c := newClient(8081, time.Second)
	c.base = s.URL + "/api/v1/"
	t.Cleanup(c.http.CloseIdleConnections)
	return c, f
}

func key(m *model, s string) tea.Cmd {
	k := tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(s)}
	switch s {
	case "enter":
		k.Type = tea.KeyEnter
	case "esc":
		k.Type = tea.KeyEsc
	case "down":
		k.Type = tea.KeyDown
	case "up":
		k.Type = tea.KeyUp
	case "left":
		k.Type = tea.KeyLeft
	case "right":
		k.Type = tea.KeyRight
	case "end":
		k.Type = tea.KeyEnd
	case "pgdown":
		k.Type = tea.KeyPgDown
	case "tab":
		k.Type = tea.KeyTab
	}
	_, cmd := m.Update(k)
	return cmd
}
func complete(t *testing.T, m *model, cmd tea.Cmd) tea.Cmd {
	t.Helper()
	if cmd == nil {
		t.Fatal("预期产生后台请求")
	}
	_, next := m.Update(cmd())
	for m.screen == worldsScreen && (m.worldLoading || m.busy) && next != nil {
		_, next = m.Update(next())
	}
	return next
}

func chooseWorldAction(t *testing.T, m *model, action string) tea.Cmd {
	t.Helper()
	detail := action == "abandon" || action == "delete" || action == "restore" || action == "backup-delete"
	if m.worldPane != detail {
		key(m, " ")
	}
	menu := m.worldMenu()
	for i := 0; i < len(menu); i++ {
		if menu[m.worldButtons[m.worldZone()]].action == action {
			return key(m, "enter")
		}
		key(m, "right")
	}
	t.Fatalf("未找到世界操作按钮：%s", action)
	return nil
}

func choosePageAction(t *testing.T, m *model, action string) tea.Cmd {
	t.Helper()
	items := m.menuItems()
	for range items {
		if items[m.menuIndex()].action == action {
			return key(m, "enter")
		}
		key(m, "right")
	}
	t.Fatalf("未找到页面操作：%s", action)
	return nil
}

func goToPage(t *testing.T, m *model, target screen) {
	t.Helper()
	if m.screen == target {
		if target == worldsScreen {
			complete(t, m, chooseWorldAction(t, m, "refresh"))
		} else {
			complete(t, m, choosePageAction(t, m, "refresh"))
		}
		return
	}
	for m.screen != homeScreen {
		cmd := key(m, "esc")
		if cmd != nil {
			complete(t, m, cmd)
		}
	}
	key(m, "home")
	if target == usersScreen {
		key(m, "right")
	}
	complete(t, m, key(m, "enter"))
}

func openUsersFromWorlds(t *testing.T, m *model) {
	t.Helper()
	if m.worldPane {
		key(m, "esc")
	}
	complete(t, m, key(m, "esc"))
	key(m, "home")
	key(m, "right")
	complete(t, m, key(m, "enter"))
}
func submitValue(t *testing.T, m *model, value string) tea.Cmd {
	t.Helper()
	m.input.SetValue(value)
	return key(m, "enter")
}

// 验证首页只在回车后进入选中入口，左右方向键与 AD 等价，刷新不重置焦点，ESC 在顶层保持原地。
func TestHomeCursorNavigation(t *testing.T) {
	c, f := testClient(t, func(path string, _ object) (int, object) {
		switch path {
		case "worlds":
			return 200, object{"worlds": []any{}}
		case "users":
			return 200, object{"users": []any{}}
		default:
			return 200, object{"version": "fixture"}
		}
	})
	m := newModel(c, journal{t.TempDir()}, 0)
	for _, direction := range []string{"right", "d", "D", "left", "a", "A"} {
		m.cursor[homeScreen] = 0
		if cmd := key(m, direction); cmd != nil || m.screen != homeScreen || len(f.requests) != 0 {
			t.Fatal("移动焦点触发了页面操作")
		}
		expected := 1
		if direction == "left" || strings.EqualFold(direction, "a") {
			expected = len(m.homeMenu()) - 1
		}
		if m.cursor[homeScreen] != expected {
			t.Fatalf("%s 未移动至预期菜单项", direction)
		}
	}
	key(m, "home")
	complete(t, m, key(m, "enter"))
	if m.screen != worldsScreen {
		t.Fatal("未进入选中的世界管理")
	}
	complete(t, m, key(m, "esc"))
	if m.screen != homeScreen || m.cursor[homeScreen] != 0 {
		t.Fatal("未返回首页并保留入口焦点")
	}
	key(m, "right")
	complete(t, m, key(m, "enter"))
	if m.screen != usersScreen {
		t.Fatal("未进入选中的用户管理")
	}
	complete(t, m, key(m, "esc"))
	before := len(f.requests)
	if cmd := key(m, "esc"); cmd != nil || m.screen != homeScreen || len(f.requests) != before {
		t.Fatal("顶层 ESC 产生了操作")
	}
	key(m, "home")
	refresh := m.load()
	key(m, "right") // 后台自动刷新过程中仍可移动焦点。
	complete(t, m, refresh)
	if m.cursor[homeScreen] != 1 {
		t.Fatal("刷新重置了光标")
	}
	view := ansi.Strip(m.View())
	if strings.Count(view, "世界管理") != 1 || strings.LastIndex(view, "世界管理") < strings.LastIndex(view, "╰") || strings.Contains(view, "[Esc]") {
		t.Fatal("首页入口未集中到底部或仍显示顶层返回提示")
	}
	m.pending = &operation{Path: "worlds/restore", Title: "恢复", Target: "世界", Body: object{"worldId": "world", "restoreId": "same", "confirm": true}}
	m.cursor[homeScreen] = 2
	if cmd := key(m, "enter"); cmd != nil || m.screen != operationScreen {
		t.Fatal("未打开待确认操作，或打开时已重发请求")
	}
	complete(t, m, key(m, "esc"))
	key(m, "end")
	quit := key(m, "enter")
	if quit == nil {
		t.Fatal("退出项没有响应")
	}
	if _, ok := quit().(tea.QuitMsg); !ok {
		t.Fatal("退出项没有结束终端")
	}
}

// 验证恢复必须经过预览和名称确认，202 及再次请求始终保留大整数任期与同一操作编号。
func TestRestoreConfirmationAndPolling(t *testing.T) {
	preview := object{"worldId": "world-1", "restoreId": "restore-1", "backupVersion": json.Number("5"), "expectedVersion": json.Number("8"), "expectedEpoch": json.Number("9007199254740993"), "manifestHash": "abc", "confirm": false}
	calls := 0
	c, f := testClient(t, func(path string, body object) (int, object) {
		switch path {
		case "worlds/restore-preview":
			return 200, preview
		case "worlds/backups":
			return 200, object{"backups": []any{object{"version": 5}}, "canRestore": true}
		case "worlds/restore":
			calls++
			if calls == 1 {
				return 202, object{"pending": true}
			}
			return 200, object{"version": 9}
		}
		return 200, object{}
	})
	m := newModel(c, journal{t.TempDir()}, 0)
	m.world = object{"worldId": "world-1", "name": "中文世界"}
	m.screen = backupsScreen
	m.backups = []object{{"version": json.Number("5")}}
	complete(t, m, key(m, "enter"))
	if m.screen != confirmScreen {
		t.Fatal("缺少确认页")
	}
	if cmd := submitValue(t, m, "错误名称"); cmd != nil || calls != 0 {
		t.Fatal("名称不匹配仍提交")
	}
	complete(t, m, key(m, "esc"))
	if calls != 0 {
		t.Fatal("取消触发了恢复")
	}
	complete(t, m, key(m, "enter"))
	next := complete(t, m, submitValue(t, m, "中文世界"))
	if next == nil || m.pending == nil {
		t.Fatal("202 未保留待确认操作")
	}
	stored, err := m.store.load()
	if err != nil || text(stored.Body, "expectedEpoch") != "9007199254740993" {
		t.Fatalf("任期未原样保存：%v", err)
	}
	complete(t, m, choosePageAction(t, m, "retry"))
	if m.pending != nil || m.screen != resultScreen {
		t.Fatal("成功后未清除操作记录")
	}
	if saved, err := m.store.load(); err != nil || saved != nil {
		t.Fatal("成功后仍保留待确认记录")
	}
	var bodies []object
	for _, r := range f.requests {
		if r.path == "worlds/restore" {
			bodies = append(bodies, r.body)
		}
	}
	if len(bodies) != 2 || !reflect.DeepEqual(bodies[0], bodies[1]) || bodies[0]["confirm"] != true {
		t.Fatal("轮询未使用同一确认请求")
	}
	complete(t, m, key(m, "esc"))
	if m.screen != backupsScreen {
		t.Fatal("恢复结果页未返回上一级备份列表")
	}
}

// 验证删除和放弃使用服务端预览；未确认不执行，放弃原因进入原请求，409 后要求重新预览。
func TestDestructiveWorldActions(t *testing.T) {
	for _, action := range []string{"delete", "abandon"} {
		t.Run(action, func(t *testing.T) {
			executed := 0
			preview := object{"worldId": "world", "expectedEpoch": json.Number("21"), "expectedVersion": nil, "confirm": false}
			if action == "delete" {
				preview["deleteId"] = "delete-1"
				preview["expectedName"] = "目标世界"
				preview["metadataHash"] = "hash"
			} else {
				preview["abandonId"] = "abandon-1"
				preview["expectedPhase"] = "STOPPING"
				preview["ownerUsername"] = "Host"
				preview["lastCommittedAt"] = nil
				preview["reason"] = ""
			}
			c, _ := testClient(t, func(path string, body object) (int, object) {
				if strings.HasSuffix(path, "-preview") {
					return 200, preview
				}
				if path == "worlds/"+action {
					executed++
					if action == "abandon" && text(body, "reason") != "测试原因" {
						t.Error("审计原因未发送")
					}
					return 409, object{"code": "WORLD_CHANGED", "message": "changed"}
				}
				return 200, object{}
			})
			m := newModel(c, journal{t.TempDir()}, 0)
			m.screen = worldsScreen
			m.world = object{"worldId": "world", "name": "目标世界", "owner": object{"username": "Host"}}
			m.worlds, m.detail = []object{m.world}, object{"world": m.world}
			if action == "delete" {
				complete(t, m, chooseWorldAction(t, m, "delete"))
			} else {
				chooseWorldAction(t, m, "abandon")
				complete(t, m, submitValue(t, m, "测试原因"))
			}
			if executed != 0 || m.screen != confirmScreen {
				t.Fatal("预览阶段产生写操作")
			}
			complete(t, m, submitValue(t, m, "目标世界"))
			if executed != 1 || m.pending != nil || !strings.Contains(m.notice, "WORLD_CHANGED") {
				t.Fatal("冲突后未清理请求并提示刷新")
			}
		})
	}
}

// 验证服务器失败后待确认操作可跨进程恢复，使用原请求重试；此时阻止发起第二个写操作。
func TestUncertainOperationSurvivesRestart(t *testing.T) {
	calls := 0
	c, f := testClient(t, func(path string, body object) (int, object) {
		calls++
		if calls == 1 {
			return 503, object{"code": "BACKUP_OPERATION_FAILED"}
		}
		return 200, object{"version": 2}
	})
	store := journal{t.TempDir()}
	op := &operation{"worlds/restore", "恢复", "世界", object{"worldId": "world", "restoreId": "same-id", "expectedEpoch": json.Number("9223372036854775806"), "confirm": true}}
	if err := store.save(op); err != nil {
		t.Fatal(err)
	}
	m := newModel(c, store, 0)
	m.pending = op
	m.screen = operationScreen
	complete(t, m, choosePageAction(t, m, "retry"))
	if m.pending == nil {
		t.Fatal("503 丢失原请求")
	}
	m2 := newModel(c, store, 0)
	var err error
	m2.pending, err = store.load()
	if err != nil {
		t.Fatal(err)
	}
	m2.screen = worldsScreen
	chooseWorldAction(t, m2, "create")
	if m2.screen != operationScreen || calls != 1 {
		t.Fatal("未完成操作期间允许另一次写入")
	}
	complete(t, m2, choosePageAction(t, m2, "retry"))
	if len(f.requests) != 2 || !reflect.DeepEqual(f.requests[0].body, f.requests[1].body) {
		t.Fatal("重启后请求身份变化")
	}
}

// 验证创建与改名的请求字段，以及从首页进入账户管理完成创建、禁用、启用、重置和 Token 清理。
func TestWorldAndUserManagement(t *testing.T) {
	world := object{"worldId": "world", "name": "旧名"}
	user := object{"username": "Player", "enabled": true}
	token := "secret-token-only-in-memory"
	c, f := testClient(t, func(path string, body object) (int, object) {
		switch path {
		case "worlds":
			if body != nil {
				return 201, world
			}
			return 200, object{"worlds": []any{world}}
		case "worlds/detail", "worlds/rename":
			return 200, object{"world": world}
		case "users":
			return 200, object{"users": []any{user}}
		case "register":
			return 201, object{"username": "Player", "token": token}
		case "users/disable":
			user["enabled"] = false
			return 200, user
		case "users/enable":
			user["enabled"] = true
			return 200, user
		}
		return 200, object{}
	})
	m := newModel(c, journal{t.TempDir()}, 0)
	goToPage(t, m, worldsScreen)
	chooseWorldAction(t, m, "create")
	complete(t, m, submitValue(t, m, "新世界"))
	goToPage(t, m, worldsScreen)
	chooseWorldAction(t, m, "rename")
	complete(t, m, submitValue(t, m, "新名"))
	goToPage(t, m, worldsScreen)
	openUsersFromWorlds(t, m)
	choosePageAction(t, m, "create-user")
	complete(t, m, submitValue(t, m, "Player"))
	if strings.Contains(m.View(), token) {
		t.Fatal("默认泄露 Token")
	}
	choosePageAction(t, m, "toggle-token")
	if !strings.Contains(m.View(), token) {
		t.Fatal("无法查看新 Token")
	}
	complete(t, m, key(m, "esc"))
	if m.result != nil || strings.Contains(m.View(), token) {
		t.Fatal("离开页面未清除 Token")
	}
	if m.screen != usersScreen {
		t.Fatal("账户操作结果未返回用户列表")
	}
	for i := 0; i < 2; i++ {
		choosePageAction(t, m, "toggle-user")
		complete(t, m, submitValue(t, m, "Player"))
		goToPage(t, m, usersScreen)
	}
	choosePageAction(t, m, "reset-token")
	complete(t, m, submitValue(t, m, "Player"))
	paths := map[string]int{}
	for _, r := range f.requests {
		paths[r.path]++
		if r.path == "worlds/rename" && (r.body["expectedName"] != "旧名" || r.body["name"] != "新名") {
			t.Fatal("改名缺少原名称约束")
		}
	}
	if paths["register"] != 2 || paths["users/disable"] != 1 || paths["users/enable"] != 1 {
		t.Fatal("账户操作缺失")
	}
	entries, err := os.ReadDir(m.store.dir)
	if err != nil || len(entries) != 0 {
		t.Fatal("账户操作产生了本地凭据文件")
	}
}

// 验证各页面在不同终端尺寸下不溢出，常用概览完整可见、确认输入框保留，且忽略过时响应。
func TestNavigationResizeAndStaleReplies(t *testing.T) {
	m := newModel(newClient(1, time.Second), journal{t.TempDir()}, 0)
	m.screen = worldsScreen
	for i := 0; i < 100; i++ {
		m.worlds = append(m.worlds, object{"worldId": fmt.Sprint(i), "name": "中文世界长名称", "status": "RECOVERY_REQUIRED"})
	}
	key(m, "end")
	if m.cursor[worldsScreen] != 99 {
		t.Fatal("无法移动到最后一条")
	}
	m.status = object{"version": "0.1.0-SNAPSHOT", "javaVersion": "21.0.13", "worlds": json.Number("3"), "ownedWorlds": json.Number("2"), "users": json.Number("8"), "onlineUsers": json.Number("3"), "startedAt": "2026-11-09T13:51:41Z", "heap": object{"usedBytes": json.Number("10485760"), "maxBytes": json.Number("268435456")}, "storage": object{"worlds": object{"totalBytes": json.Number("62984577024"), "usableBytes": json.Number("23153369088")}}}
	m.world = object{"worldId": "6a21e0d4-354b-4b0e-bd06-93169265a87e", "name": "中文世界", "lastCommittedAt": "2026-11-09T13:51:41Z"}
	m.users = []object{{"username": "Player", "enabled": true}}
	m.backups = []object{{"version": json.Number("5"), "committedAt": "2026-11-09T13:51:41Z"}}
	m.events = []object{{"epoch": json.Number("10"), "type": "RESTORE", "reason": "管理操作原因"}}
	m.form = form{title: "创建共享世界", hint: "输入世界名称", back: worldsScreen}
	m.confirmation = &confirmation{operation: operation{"worlds/delete", "删除世界", "中文世界", object{"worldId": m.world["worldId"]}}, warning: "请确认操作影响", back: worldsScreen}
	m.result = object{"version": json.Number("6"), "committedAt": "2026-11-09T13:51:41Z"}
	for _, page := range []screen{homeScreen, worldsScreen, usersScreen, detailScreen, backupsScreen, historyScreen, formScreen, confirmScreen, resultScreen, operationScreen} {
		m.screen = page
		for _, size := range [][2]int{{120, 30}, {100, 30}, {80, 24}, {60, 20}, {36, 12}} {
			m.Update(tea.WindowSizeMsg{Width: size[0], Height: size[1]})
			view := m.View()
			if len(strings.Split(strings.TrimSuffix(view, "\n"), "\n")) > size[1] {
				t.Fatal("界面超出终端高度")
			}
			for _, line := range strings.Split(view, "\n") {
				if ansi.StringWidth(line) > size[0] {
					t.Fatalf("页面 %d 超过终端宽度 %d", page, size[0])
				}
			}
			if page == homeScreen && size == [2]int{80, 24} {
				view = ansi.Strip(view)
				if !strings.Contains(view, localTime("2026-11-09T13:51:41Z")) || !strings.Contains(view, "21.6 GiB") {
					t.Fatal("标准终端首屏未完整显示常用服务指标")
				}
				if !strings.Contains(view, "总计 3 · 有房主 2") || !strings.Contains(view, "总计 8 · 在线 3") {
					t.Fatal("未区分世界与用户的总数和当前数量")
				}
				for range 20 {
					m.Update(tea.MouseMsg{X: 5, Y: 5, Button: tea.MouseButtonWheelDown, Action: tea.MouseActionPress})
				}
				if !strings.Contains(ansi.Strip(m.View()), "累计发送") {
					t.Fatal("翻页后未显示完整的网络指标")
				}
				m.scroll = 0
			}
		}
	}
	m.screen = homeScreen
	m.Update(tea.WindowSizeMsg{Width: 100, Height: 30})
	m.notice = "管理端口连接失败"
	view := ansi.Strip(m.View())
	errorAt := strings.Index(view, m.notice)
	if errorAt < strings.Index(view, "╭─ 首页") || errorAt > strings.LastIndex(view, "╰") {
		t.Fatal("异常状态未显示在服务概览内")
	}
	m.notice = ""
	m.Update(tea.WindowSizeMsg{Width: 36, Height: 12})
	m.ask(operation{"worlds/delete", "删除", "测试世界", object{"expectedEpoch": json.Number("20")}}, strings.Repeat("风险说明", 30), worldsScreen)
	if !strings.Contains(m.View(), "> ") {
		t.Fatal("小终端无法看到确认输入框")
	}
	m.serial = 5
	m.Update(replyMsg{serial: 4, kind: "worlds", response: response{200, object{}}})
	if len(m.worlds) != 100 {
		t.Fatal("过时响应覆盖当前页面")
	}
	_, cmd := m.Update(pollMsg{serial: 4})
	if cmd != nil {
		t.Fatal("旧轮询启动了请求")
	}
	if strings.Contains(clean("a\x1b[2J\u202eb\n"), "\x1b") || strings.Contains(clean("\u202e"), "\u202e") {
		t.Fatal("未去除终端控制字符")
	}
}

// 验证请求失败不会自动重发、拒绝重定向并限制请求体，同时提供可定位的服务端错误。
func TestHTTPErrorAndRedirectBoundary(t *testing.T) {
	c, f := testClient(t, func(path string, body object) (int, object) {
		return 409, object{"code": "WORLD_CHANGED", "message": "new owner"}
	})
	_, err := c.call(context.Background(), "worlds/delete", object{"confirm": true})
	if err == nil || !strings.Contains(err.Error(), "WORLD_CHANGED") || !strings.Contains(err.Error(), "test-request") || len(f.requests) != 1 {
		t.Fatal("错误或请求次数不正确")
	}
	_, err = c.call(context.Background(), "register", object{"username": strings.Repeat("x", 4096)})
	if err == nil || len(f.requests) != 1 {
		t.Fatal("超大请求被发送")
	}
	hits := 0
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits++; http.Redirect(w, r, "/other", 302) }))
	defer s.Close()
	c.base = s.URL + "/"
	_, err = c.call(context.Background(), "status", nil)
	if err == nil || hits != 1 {
		t.Fatal("跟随了管理接口重定向")
	}
}

// 验证无法写入重试记录时不发送危险请求，并拒绝从记录文件重放账户或未确认操作。
func TestJournalFailurePreventsMutation(t *testing.T) {
	c, f := testClient(t, func(string, object) (int, object) { return 200, object{} })
	path := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(path, []byte("occupied"), 0600); err != nil {
		t.Fatal(err)
	}
	m := newModel(c, journal{path}, 0)
	m.ask(operation{"worlds/delete", "删除", "世界", object{"worldId": "world", "deleteId": "id", "confirm": false}}, "风险", worldsScreen)
	if cmd := submitValue(t, m, "世界"); cmd != nil || len(f.requests) != 0 || !strings.Contains(m.notice, "未发送") {
		t.Fatal("记录失败仍执行了请求")
	}
	store := journal{t.TempDir()}
	if err := store.save(&operation{"register", "账户", "Player", object{"username": "Player", "confirm": true}}); err == nil {
		t.Fatal("允许重放注册")
	}
	if _, err := decodeObject([]byte(`{"a":1} {"b":2}`)); err == nil {
		t.Fatal("允许 JSON 后追加内容")
	}
}
