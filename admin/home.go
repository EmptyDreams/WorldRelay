package main

import (
	"strings"

	tea "github.com/charmbracelet/bubbletea"
)

type homeAction int

const (
	homeWorlds homeAction = iota
	homeUsers
	homePending
	homeQuit
)

type homeItem struct {
	label  string
	action homeAction
}

func (m model) homeMenu() []homeItem {
	items := []homeItem{{"世界管理", homeWorlds}, {"用户管理", homeUsers}}
	if m.pending != nil {
		items = append(items, homeItem{"待确认操作", homePending})
	}
	return append(items, homeItem{"退出", homeQuit})
}

// 首页的方向键只移动菜单焦点，操作必须由 Enter 明确触发。
func (m *model) homeKey(key string) (tea.Cmd, bool) {
	items := m.homeMenu()
	current := max(0, min(m.cursor[homeScreen], len(items)-1))
	switch strings.ToLower(key) {
	case "left", "a":
		m.cursor[homeScreen] = (current + len(items) - 1) % len(items)
	case "right", "d":
		m.cursor[homeScreen] = (current + 1) % len(items)
	case "home":
		m.cursor[homeScreen] = 0
	case "end":
		m.cursor[homeScreen] = len(items) - 1
	case "esc": // 首页已是顶层，保持原地，不刷新或退出。
	case "enter":
		if items[current].action == homeQuit {
			return tea.Quit, true
		}
		if m.busy {
			return nil, true
		}
		switch items[current].action {
		case homeWorlds:
			return m.navigate(worldsScreen), true
		case homeUsers:
			return m.navigate(usersScreen), true
		case homePending:
			m.screen, m.scroll = operationScreen, 0
		}
	default:
		return nil, false
	}
	return nil, true
}

func (m model) parentScreen() screen {
	switch m.screen {
	case detailScreen:
		return worldsScreen
	case backupsScreen:
		return detailScreen
	case historyScreen:
		return worldsScreen
	case resultScreen:
		return m.resultBack
	default:
		return homeScreen
	}
}
