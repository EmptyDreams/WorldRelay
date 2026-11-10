package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/x/ansi"
)

// 验证放弃按钮随持有关系启停，键盘跳过禁用项，离线房主与相同基础版本仍可恢复或放弃。
func TestAbandonButtonTracksUncommittedOwnership(t *testing.T) {
	m := newModel(newClient(1, time.Second), journal{t.TempDir()}, 0)
	free := object{"worldId": "world", "name": "测试世界", "phase": "FREE", "status": "FREE"}
	m.screen, m.worldPane, m.world = worldsScreen, true, free
	m.worlds, m.detail = []object{free}, object{"world": free}
	m.normalizeWorldButton()
	if m.worldButtons[1] != 0 {
		t.Fatal("世界详情没有默认选择刷新")
	}
	key(m, "left")
	if m.worldButtons[1] != 4 {
		t.Fatal("刷新左侧没有按顺序排列删除世界")
	}
	key(m, "left")
	if m.worldButtons[1] != 2 {
		t.Fatal("向左切换没有跳过禁用的放弃进度")
	}
	key(m, "d")
	if m.worldButtons[1] != 4 {
		t.Fatal("向右切换没有跳过禁用的放弃进度")
	}
	key(m, "d")
	if m.worldButtons[1] != 0 {
		t.Fatal("向右切换没有跳过禁用项")
	}
	m.worldButtons[1] = 3
	if cmd := key(m, "enter"); cmd != nil || m.screen != worldsScreen {
		t.Fatal("禁用的放弃进度触发了操作")
	}
	owned := object{"worldId": "world", "name": "测试世界", "phase": "RUNNING", "status": "RECOVERY_REQUIRED", "online": false,
		"owner": object{"username": "Host"}, "baseVersion": json.Number("3"), "committedVersion": json.Number("3")}
	m.world, m.worlds, m.detail = owned, []object{owned}, object{"world": owned}
	key(m, "enter")
	if m.screen != formScreen || m.form.kind != "abandon" {
		t.Fatal("离线持有者的未上传会话被错误禁用")
	}
	m.screen, m.worldDetailSeq = worldsScreen, 7
	m.Update(worldDetailMsg{sequence: 7, worldID: "world", detail: object{"world": free}, backups: object{}})
	if m.worldActionEnabled("abandon") || m.worldButtons[1] != 0 {
		t.Fatal("持有关系释放后仍保留放弃进度焦点")
	}
}

// 验证世界选择自动加载右侧详情、过期响应隔离、独立滚轮，以及备份删除的预览与名称确认。
func TestWorldWorkspaceSelectionAndBackupActions(t *testing.T) {
	worlds := []any{object{"worldId": "first", "name": "一号世界", "status": "FREE"}, object{"worldId": "second", "name": "二号世界", "status": "RUNNING"}}
	writes := 0
	c, f := testClient(t, func(path string, body object) (int, object) {
		switch path {
		case "worlds":
			return 200, object{"worlds": worlds}
		case "worlds/detail":
			return 200, object{"world": object{"worldId": body["worldId"], "name": "一号世界", "previousOwner": "Earlier", "online": false}, "storage": object{"currentBytes": json.Number("1024")}}
		case "worlds/backups":
			return 200, object{"backups": []any{object{"version": json.Number("2"), "current": true}, object{"version": json.Number("1"), "current": false}}}
		case "worlds/backup-delete-preview":
			return 200, object{"worldId": body["worldId"], "backupVersion": body["backupVersion"], "deleteId": "fixed-delete", "snapshotId": "old-snapshot", "expectedEpoch": json.Number("9007199254740993"), "expectedVersion": json.Number("2"), "confirm": false}
		case "worlds/backup-delete":
			writes++
			return 200, object{"deleted": true}
		}
		return 200, object{}
	})
	m := newModel(c, journal{t.TempDir()}, 0)
	complete(t, m, m.navigate(worldsScreen))
	if text(m.world, "worldId") != "first" || text(m.world, "previousOwner") != "Earlier" {
		t.Fatal("选中世界后未自动读取详情")
	}
	older := key(m, "down")
	newer := key(m, "up")
	m.Update(older())
	if text(m.world, "worldId") != "first" {
		t.Fatal("迟到响应覆盖了新选择")
	}
	m.Update(newer())
	m.Update(tea.WindowSizeMsg{Width: 100, Height: 20})
	_, cmd := m.Update(tea.MouseMsg{X: 50, Y: 5, Button: tea.MouseButtonWheelDown, Action: tea.MouseActionPress})
	if cmd != nil || m.worldOffset != 3 || m.cursor[worldsScreen] != 0 {
		t.Fatal("右侧滚轮改变了左侧选择")
	}
	key(m, " ")
	key(m, "down")
	if !strings.Contains(ansi.Strip(m.View()), "#1") {
		t.Fatal("所选备份未滚动到可见范围")
	}
	before := len(f.requests)
	key(m, "d")
	if writes != 0 || len(f.requests) != before || m.screen != worldsScreen {
		t.Fatal("切换按钮触发了操作")
	}
	complete(t, m, chooseWorldAction(t, m, "backup-delete"))
	if m.screen != confirmScreen || text(m.confirmation.Body, "backupVersion") != "1" {
		t.Fatal("没有预览选中的历史备份")
	}
	complete(t, m, key(m, "esc"))
	if writes != 0 {
		t.Fatal("取消仍删除了备份")
	}
	complete(t, m, chooseWorldAction(t, m, "backup-delete"))
	if cmd := submitValue(t, m, "错误名称"); cmd != nil || writes != 0 {
		t.Fatal("未确认完整名称仍删除备份")
	}
	complete(t, m, submitValue(t, m, "一号世界"))
	if writes != 1 || m.resultBack != worldsScreen || m.pending != nil {
		t.Fatal("删除后没有回到世界管理上下文")
	}
	for _, request := range f.requests {
		if request.path == "worlds/backup-delete" && (request.body["confirm"] != true || text(request.body, "expectedEpoch") != "9007199254740993") {
			t.Fatal("确认请求改变了原任期或确认字段")
		}
	}
}
