package main

import (
	"strings"
	"testing"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/x/ansi"
)

// 验证账户与结果页通过菜单执行，表单编辑保留字母和方向键，Esc 取消不会执行确认操作。
func TestSharedMenuPreservesInputEditingAndConfirmation(t *testing.T) {
	writes := 0
	user := object{"username": "Guest", "enabled": true}
	c, _ := testClient(t, func(path string, body object) (int, object) {
		if path == "users/disable" {
			writes++
		}
		return 200, object{"users": []any{user}}
	})
	m := newModel(c, journal{t.TempDir()}, 0)
	complete(t, m, m.navigate(usersScreen))
	if cmd := key(m, "right"); cmd != nil || m.menuIndex() != 1 {
		t.Fatal("切换菜单触发了操作")
	}
	key(m, "enter")
	if m.screen != confirmScreen {
		t.Fatal("未打开所选账户操作的确认页")
	}
	typed := "ad tab enter"
	m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(typed)})
	key(m, "left")
	if m.input.Value() != typed || m.input.Position() != len([]rune(typed))-1 || m.menuIndex() != 0 {
		t.Fatal("输入框编辑被菜单快捷键劫持")
	}
	complete(t, m, key(m, "esc"))
	if writes != 0 || m.screen != usersScreen {
		t.Fatal("取消操作仍执行了账户变更")
	}
	m.screen, m.resultBack, m.result = resultScreen, usersScreen, object{"version": 1}
	if !strings.Contains(ansi.Strip(m.View()), "▸返回") {
		t.Fatal("成功页未显示统一菜单")
	}
	complete(t, m, key(m, "enter"))
	if m.screen != usersScreen {
		t.Fatal("成功页未通过回车返回")
	}
}
