package main

import (
	"strings"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

type menuItem struct {
	label, action string
	enabled       bool
}

func (m model) menuItems() []menuItem {
	item := func(label, action string, enabled bool) menuItem { return menuItem{label, action, enabled} }
	ready := !m.busy
	switch m.screen {
	case homeScreen:
		var items []menuItem
		for _, entry := range m.homeMenu() {
			items = append(items, item(entry.label, "", true))
		}
		return items
	case worldsScreen:
		var items []menuItem
		for _, entry := range m.worldMenu() {
			items = append(items, item(entry.label, entry.action, m.worldActionEnabled(entry.action)))
		}
		return items
	case usersScreen:
		label := "启用账户"
		if len(m.users) > 0 && flagValue(m.users[max(0, min(m.cursor[usersScreen], len(m.users)-1))], "enabled") {
			label = "禁用账户"
		}
		return []menuItem{item("创建账户", "create-user", ready), item(label, "toggle-user", ready && len(m.users) > 0), item("重置 Token", "reset-token", ready && len(m.users) > 0), item("刷新", "refresh", ready), item("返回", "back", true)}
	case worldModeScreen:
		return []menuItem{item("仅允许正版账号", "create-online-world", ready), item("允许离线账号", "create-offline-world", ready)}
	case formScreen, confirmScreen:
		return nil
	case resultScreen:
		if _, ok := m.result["token"].(string); ok {
			label := "显示 Token"
			if m.reveal {
				label = "隐藏 Token"
			}
			return []menuItem{item(label, "toggle-token", true), item("返回", "back", true)}
		}
		return []menuItem{item("返回", "back", true)}
	case operationScreen:
		if m.pending != nil {
			return []menuItem{item("重试", "retry", ready), item("返回", "back", true)}
		}
		return []menuItem{item("返回", "back", true)}
	case backupsScreen:
		return []menuItem{item("恢复备份", "restore", ready && len(m.backups) > 0), item("刷新", "refresh", ready), item("返回", "back", true)}
	case historyScreen:
		return []menuItem{item("加载更多", "history-next", ready && m.historyInfo["nextBeforeEpoch"] != nil), item("刷新", "refresh", ready), item("返回", "back", true)}
	case detailScreen:
		return []menuItem{item("改名", "rename", ready), item("放弃进度", "abandon", ready && m.worldActionEnabled("abandon")), item("删除世界", "delete", ready), item("刷新", "refresh", ready), item("返回", "back", true)}
	}
	return []menuItem{item("返回", "back", true)}
}

func (m model) menuIndex() int {
	switch m.screen {
	case homeScreen:
		return m.cursor[homeScreen]
	case worldsScreen:
		return m.worldButtons[m.worldZone()]
	}
	return max(0, min(m.pageButtons[m.screen], len(m.menuItems())-1))
}

func renderMenu(items []menuItem, current, width int, focused bool) string {
	current = max(0, min(current, len(items)-1))
	render := func(start, end int) string {
		var buttons []string
		for i := start; i < end; i++ {
			label := " " + items[i].label + " "
			if !items[i].enabled {
				label = muted.Faint(true).Render(label)
			} else if i == current && focused {
				label = activeTab.Render("▸" + items[i].label + " ")
			} else {
				label = muted.Render(label)
			}
			buttons = append(buttons, label)
		}
		line := strings.Join(buttons, borderStyle.Render("│"))
		if start > 0 {
			line = "‹ " + line
		}
		if end < len(items) {
			line += " ›"
		}
		return line
	}
	start, end := 0, len(items)
	for end-start > 1 && lipgloss.Width(render(start, end)) > width {
		if current-start > end-current-1 {
			start++
		} else {
			end--
		}
	}
	return render(start, end)
}

func (m *model) pageMenuKey(key string) tea.Cmd {
	items := m.menuItems()
	current := m.menuIndex()
	switch strings.ToLower(key) {
	case "left", "a", "right", "d":
		direction := 1
		if key == "left" || strings.EqualFold(key, "a") {
			direction = -1
		}
		for range items {
			current = (current + direction + len(items)) % len(items)
			if items[current].enabled {
				break
			}
		}
		m.pageButtons[m.screen] = current
	case "esc":
		return m.returnToParent()
	case "enter":
		if items[current].enabled {
			return m.performMenuAction(items[current].action)
		}
	case "up", "down", "home", "end":
		if m.isTable() {
			delta := 1
			if key == "up" {
				delta = -1
			}
			m.cursor[m.screen] += delta
			if key == "home" {
				m.cursor[m.screen] = 0
			}
			if key == "end" {
				m.cursor[m.screen] = m.rowCount() - 1
			}
			m.clampCursor()
		}
	}
	return nil
}

func (m *model) returnToParent() tea.Cmd {
	if m.screen == worldModeScreen {
		if m.busy {
			return nil
		}
		m.screen = worldsScreen
		return m.openForm("create-world", "创建共享世界", "输入世界名称；首次房主仍在 Minecraft 内创建实际存档。", m.newWorldName)
	}
	if m.screen == formScreen || m.screen == confirmScreen {
		if m.busy {
			return nil
		}
		back := m.form.back
		if m.screen == confirmScreen {
			back = m.confirmation.back
			m.confirmation = nil
		}
		m.input.Blur()
		return m.navigate(back)
	}
	return m.navigate(m.parentScreen())
}

func (m *model) performMenuAction(action string) tea.Cmd {
	switch action {
	case "create-online-world", "create-offline-world":
		if !m.busy {
			return m.request("worlds", object{"name": m.newWorldName, "allowOfflinePlayers": action == "create-offline-world"}, "mutation")
		}
	case "back":
		return m.returnToParent()
	case "refresh":
		return m.load()
	case "toggle-token":
		m.reveal = !m.reveal
	case "retry":
		if m.pending != nil {
			return m.request(m.pending.Path, m.pending.Body, "execute")
		}
	case "create-user":
		if m.canMutate() {
			return m.openForm("create-user", "创建玩家账户", "输入玩家名称；成功后可查看 Token。", "")
		}
	case "toggle-user", "reset-token":
		if len(m.users) == 0 || !m.canMutate() {
			return nil
		}
		user := m.users[m.cursor[usersScreen]]
		name := text(user, "username")
		if action == "reset-token" {
			return m.ask(operation{"register", "重置玩家 Token", name, object{"username": name, "overwrite": true}}, "原 Token 会失效；禁用中的账户仍保持禁用。", usersScreen)
		}
		path, title, warning := "users/enable", "启用账户", "恢复现有 Token 的访问资格；旧传输授权不会复活。"
		if flagValue(user, "enabled") {
			path, title, warning = "users/disable", "禁用账户", "撤销传输授权并关闭连接；世界所有权仍保留，必要时另行放弃进度。"
		}
		return m.ask(operation{path, title, name, object{"username": name}}, warning, usersScreen)
	case "rename":
		if m.canMutate() {
			return m.openForm("rename", "修改世界名称", "只修改显示名称。", text(m.world, "name"))
		}
	case "abandon":
		if m.canMutate() {
			return m.openForm("abandon", "放弃未上传进度", "输入审计原因（最多 256 个字符）；下一步会显示服务端预览。", "")
		}
	case "delete":
		if m.canMutate() {
			return m.request("worlds/delete-preview", object{"worldId": m.world["worldId"]}, "preview-delete")
		}
	case "restore":
		if len(m.backups) > 0 && m.canMutate() {
			return m.request("worlds/restore-preview", object{"worldId": m.world["worldId"], "backupVersion": m.backups[m.cursor[backupsScreen]]["version"]}, "preview-restore")
		}
	case "history-next":
		if m.historyInfo["nextBeforeEpoch"] != nil {
			return m.request("worlds/history", object{"worldId": m.world["worldId"], "limit": 50, "beforeEpoch": m.historyInfo["nextBeforeEpoch"]}, "history-next")
		}
	}
	return nil
}
