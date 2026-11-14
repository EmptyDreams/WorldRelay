package main

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/charmbracelet/x/ansi"
)

func TestManageGlobalOperatorsThroughUserMenu(t *testing.T) {
	level, revision, writes := 0, 0, 0
	c, _ := testClient(t, func(path string, body object) (int, object) {
		if path == "users" {
			return 200, object{"operatorRevision": revision, "users": []any{object{"username": "Dev", "enabled": true, "opLevel": level, "transferAuthorizations": 0}}}
		}
		if text(body, "username") != "Dev" || integer(body, "expectedRevision") != int64(revision) || body["worldId"] != nil {
			t.Errorf("错误的全局权限请求：%s %#v", path, body)
		}
		writes++
		if path == "operators/set" {
			level = int(integer(body, "level"))
		} else if path == "operators/remove" {
			level = 0
		} else {
			t.Errorf("未知权限操作：%s", path)
		}
		revision++
		return 200, object{"revision": revision, "operators": []any{}}
	})
	m := newModel(c, journal{t.TempDir()}, 0)
	complete(t, m, m.navigate(usersScreen))
	if !strings.Contains(ansi.Strip(m.View()), "全局 OP") {
		t.Fatal("列表缺少全局 OP 列")
	}
	m.performMenuAction("set-op")
	if m.screen != formScreen {
		t.Fatal("没有打开等级输入")
	}
	m.input.SetValue("5")
	if key(m, "enter") != nil || writes != 0 {
		t.Fatal("非法等级被发送")
	}
	m.input.SetValue("3")
	complete(t, m, key(m, "enter"))
	if writes != 1 || level != 3 || m.resultBack != usersScreen {
		t.Fatal("未设置全局 OP")
	}
	if !strings.Contains(ansi.Strip(m.View()), "下次同步后生效") {
		t.Fatal("将保存配置误报为房主已应用")
	}
	complete(t, m, key(m, "enter"))
	if integer(m.users[0], "opLevel") != 3 {
		t.Fatal("返回后未刷新等级")
	}
	complete(t, m, m.performMenuAction("remove-op"))
	if writes != 2 || level != 0 {
		t.Fatal("未撤销 OP")
	}
}

func TestOperatorConflictDoesNotRetryOrOverwriteNewerPolicy(t *testing.T) {
	writes := 0
	c, _ := testClient(t, func(path string, body object) (int, object) {
		if path == "users" {
			return 200, object{"operatorRevision": json.Number("7"), "users": []any{object{"username": "Dev", "enabled": true, "opLevel": 2}}}
		}
		writes++
		return 409, object{"code": "OPERATOR_REVISION_MISMATCH", "message": "Refresh before editing"}
	})
	m := newModel(c, journal{t.TempDir()}, 0)
	complete(t, m, m.navigate(usersScreen))
	m.performMenuAction("set-op")
	m.input.SetValue("4")
	complete(t, m, key(m, "enter"))
	if writes != 1 || m.screen != formScreen || !strings.Contains(m.notice, "OPERATOR_REVISION_MISMATCH") {
		t.Fatal("并发冲突被忽略或自动重试")
	}
}
