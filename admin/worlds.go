package main

import (
	"context"
	"fmt"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/x/ansi"
)

type worldDetailMsg struct {
	sequence        uint64
	worldID         string
	detail, backups object
	err             error
}

type worldButton struct{ label, action string }

func (m model) worldMenu() []worldButton {
	if m.worldPane {
		return []worldButton{{"刷新", "refresh"}, {"恢复备份", "restore"}, {"删除备份", "backup-delete"}, {"放弃进度", "abandon"}, {"删除世界", "delete"}}
	}
	return []worldButton{{"创建世界", "create"}, {"刷新", "refresh"}, {"改名", "rename"}}
}

func (m model) worldActionEnabled(action string) bool {
	if action != "abandon" {
		return true
	}
	// 提交完成会同时释放持有关系；版本相同、房主离线均不能证明没有未上传进度。
	return !m.worldLoading && m.worldError == "" && m.detail != nil && obj(m.world, "owner") != nil
}

func (m model) nextWorldButton(current, direction int) int {
	menu := m.worldMenu()
	for range menu {
		current = (current + direction + len(menu)) % len(menu)
		if m.worldActionEnabled(menu[current].action) {
			return current
		}
	}
	return current
}

func (m *model) normalizeWorldButton() {
	menu, zone := m.worldMenu(), m.worldZone()
	current := max(0, min(m.worldButtons[zone], len(menu)-1))
	if !m.worldActionEnabled(menu[current].action) {
		current = 0
	}
	m.worldButtons[zone] = current
}

func (m model) worldZone() int {
	if m.worldPane {
		return 1
	}
	return 0
}

func (m model) selectedWorld() object {
	if len(m.worlds) == 0 {
		return nil
	}
	return m.worlds[max(0, min(m.cursor[worldsScreen], len(m.worlds)-1))]
}

func (m *model) loadWorldSelection() tea.Cmd {
	selected := m.selectedWorld()
	if text(selected, "worldId") != text(m.world, "worldId") {
		m.detail, m.backupInfo, m.backups = nil, nil, nil
		m.worldOffset, m.worldBackup = 0, 0
	}
	m.world = selected
	m.worldDetailSeq++
	if selected == nil {
		m.worldLoading, m.worldPane = false, false
		return nil
	}
	m.worldLoading, m.worldError = true, ""
	m.normalizeWorldButton()
	sequence, id, api := m.worldDetailSeq, text(selected, "worldId"), m.api
	return func() tea.Msg {
		detail, err := api.call(context.Background(), "worlds/detail", object{"worldId": id})
		var backups response
		if err == nil {
			backups, err = api.call(context.Background(), "worlds/backups", object{"worldId": id})
		}
		return worldDetailMsg{sequence, id, detail.body, backups.body, err}
	}
}

func (m *model) moveWorld(delta int) tea.Cmd {
	previous := m.cursor[worldsScreen]
	m.cursor[worldsScreen] += delta
	m.clampCursor()
	if previous == m.cursor[worldsScreen] {
		return nil
	}
	return m.loadWorldSelection()
}

func (m *model) worldKey(key string) tea.Cmd {
	menu, zone := m.worldMenu(), m.worldZone()
	switch strings.ToLower(key) {
	case "left", "a":
		m.worldButtons[zone] = m.nextWorldButton(m.worldButtons[zone], -1)
	case "right", "d":
		m.worldButtons[zone] = m.nextWorldButton(m.worldButtons[zone], 1)
	case " ", "space":
		if m.selectedWorld() != nil {
			m.worldPane = !m.worldPane
			if m.worldPane {
				m.worldButtons[1] = 0
			}
			m.normalizeWorldButton()
		}
	case "esc":
		if m.worldPane {
			m.worldPane = false
			return nil
		}
		return m.navigate(homeScreen)
	case "up", "down", "home", "end":
		delta := 1
		if key == "up" {
			delta = -1
		}
		if m.worldPane {
			m.worldBackup = max(0, min(m.worldBackup+delta, len(m.backups)-1))
			if key == "home" {
				m.worldBackup = 0
			}
			if key == "end" {
				m.worldBackup = max(0, len(m.backups)-1)
			}
			m.revealWorldBackup()
		} else {
			if key == "home" {
				delta = -len(m.worlds)
			}
			if key == "end" {
				delta = len(m.worlds)
			}
			return m.moveWorld(delta)
		}
	case "enter":
		if m.busy {
			return nil
		}
		action := menu[m.worldButtons[zone]].action
		if !m.worldActionEnabled(action) {
			return nil
		}
		if action == "refresh" {
			return m.load()
		}
		if action == "create" {
			if m.canMutate() {
				return m.openForm("create-world", "创建共享世界", "输入世界名称；首次房主仍在 Minecraft 内创建实际存档。", "")
			}
			return nil
		}
		if m.selectedWorld() == nil {
			m.notice = "请先选择一个世界。"
			return nil
		}
		if m.worldLoading || m.worldError != "" || m.detail == nil {
			m.notice = "请等世界详情读取完成；读取失败时可返回列表选择刷新。"
			return nil
		}
		if !m.canMutate() {
			return nil
		}
		switch action {
		case "rename":
			return m.openForm("rename", "修改世界名称", "只修改显示名称。", text(m.world, "name"))
		case "abandon":
			return m.openForm("abandon", "放弃未上传进度", "输入审计原因（最多 256 个字符）；下一步会显示服务端预览。", "")
		case "delete":
			return m.request("worlds/delete-preview", object{"worldId": m.world["worldId"]}, "preview-delete")
		case "restore", "backup-delete":
			if len(m.backups) == 0 {
				m.notice = "没有可选择的备份。"
				return nil
			}
			backup := m.backups[m.worldBackup]
			if action == "backup-delete" && flagValue(backup, "current") {
				m.notice = "当前权威版本不能删除，请选择历史备份。"
				return nil
			}
			return m.request("worlds/"+action+"-preview", object{"worldId": m.world["worldId"], "backupVersion": backup["version"]}, "preview-"+action)
		}
	}
	return nil
}

func (m model) worldWidths() (int, int) {
	width := m.bodyWidth()
	if width < 55 {
		return width, width
	}
	left := min(30, max(20, width/3))
	return left, width - left - 1
}

func (m *model) worldWheel(msg tea.MouseMsg) tea.Cmd {
	delta := 3
	if msg.Button == tea.MouseButtonWheelUp {
		delta = -3
	} else if msg.Button != tea.MouseButtonWheelDown {
		return nil
	}
	left, right := m.worldWidths()
	if (m.bodyWidth() < 55 && !m.worldPane) || (m.bodyWidth() >= 55 && msg.X < left+2) {
		return m.moveWorld(delta)
	}
	lines, _ := m.worldDetailLines(right - 4)
	limit := max(0, len(lines)-max(1, m.bodyHeight()-2))
	m.worldOffset = max(0, min(min(m.worldOffset, limit)+delta, limit))
	return nil
}

func shortWorldStatus(value string) string {
	switch value {
	case "RECOVERY_REQUIRED", "OWNED_OFFLINE":
		return "等待恢复"
	}
	return phase(value)
}

func pane(title string, lines []string, width, height, offset int, footer string) string {
	inner := max(1, height-2)
	start := max(0, min(offset, max(0, len(lines)-inner)))
	result := []string{rule("╭", "╮", title, width)}
	for i := 0; i < inner; i++ {
		line := ""
		if start+i < len(lines) {
			line = lines[start+i]
		}
		result = append(result, borderStyle.Render("│")+" "+fit(line, width-4)+" "+borderStyle.Render("│"))
	}
	if footer == "" {
		result = append(result, borderStyle.Render("╰"+strings.Repeat("─", max(0, width-2))+"╯"))
	} else {
		result = append(result, rule("╰", "╯", footer, width))
	}
	return strings.Join(result, "\n")
}

func (m model) worldDetailLines(width int) ([]string, []int) {
	var lines []string
	add := func(value string) {
		lines = append(lines, strings.Split(ansi.Hardwrap(value, max(1, width), true), "\n")...)
	}
	heading := func(value string) {
		if len(lines) > 0 {
			add("")
		}
		add(accent.Render(value))
	}
	if m.worldError != "" {
		add(warningStyle.Render(m.worldError))
	}
	if m.selectedWorld() == nil {
		add(muted.Render("选择左侧世界查看详情。"))
		return lines, nil
	}
	if m.worldLoading {
		add(muted.Render("正在读取所选世界…"))
	}
	w := m.world
	heading("世界信息")
	for _, item := range []struct{ label, value string }{
		{"世界名称", text(w, "name")}, {"ID", text(w, "worldId")}, {"状态", phase(text(w, "status"))},
		{"最近提交", localTime(text(w, "lastCommittedAt"))}, {"持有者", text(obj(w, "owner"), "username")},
		{"上一持有者", text(w, "previousOwner")}, {"是否在线", text(w, "online")},
	} {
		if item.label == "ID" && lipgloss.Width(item.value)+13 > width {
			add(muted.Render("ID"))
			add(clean(item.value))
		} else {
			add(metric(item.label, item.value))
		}
	}
	heading("存储占用")
	storage := obj(m.detail, "storage")
	for _, item := range []struct{ key, label string }{{"currentBytes", "权威快照"}, {"backupBytes", "历史备份"}, {"temporaryBytes", "临时副本"}, {"transferBytes", "传输目录"}, {"metadataBytes", "元数据"}, {"totalBytes", "合计"}} {
		add(metric(item.label, sizeValue(storage, item.key)))
	}
	heading("备份列表")
	var backupLines []int
	for i, backup := range m.backups {
		backupLines = append(backupLines, len(lines))
		marker := "  "
		if i == m.worldBackup {
			marker = "▸ "
		}
		line := marker + "#" + text(backup, "version") + "  " + localTime(text(backup, "committedAt"))
		if flagValue(backup, "current") {
			line += "  当前"
		}
		if backup["restoredFromVersion"] != nil {
			line += " · 恢复自 #" + text(backup, "restoredFromVersion")
		}
		if m.worldPane && i == m.worldBackup {
			line = selected.Render(line)
		}
		add(line)
	}
	if len(m.backups) == 0 {
		add(muted.Render("暂无备份"))
	}
	return lines, backupLines
}

func (m *model) revealWorldBackup() {
	_, right := m.worldWidths()
	lines, positions := m.worldDetailLines(right - 4)
	if len(positions) == 0 {
		return
	}
	selected := positions[max(0, min(m.worldBackup, len(positions)-1))]
	end := len(lines)
	if m.worldBackup+1 < len(positions) {
		end = positions[m.worldBackup+1]
	}
	height := max(1, m.bodyHeight()-2)
	m.worldOffset = min(m.worldOffset, max(0, len(lines)-height))
	if selected < m.worldOffset {
		m.worldOffset = selected
	}
	if end > m.worldOffset+height {
		m.worldOffset = min(selected, max(0, end-height))
	}
}

func (m model) worldWorkspace() string {
	left, right := m.worldWidths()
	var worlds []string
	for i, w := range m.worlds {
		status := shortWorldStatus(text(w, "status"))
		marker := "  "
		if i == m.cursor[worldsScreen] {
			marker = "▸ "
		}
		line := marker + fit(text(w, "name"), max(1, left-7-lipgloss.Width(status))) + " " + status
		if i == m.cursor[worldsScreen] && !m.worldPane {
			line = selected.Render(line)
		}
		worlds = append(worlds, line)
	}
	if len(worlds) == 0 {
		worlds = []string{muted.Render("暂无世界")}
	}
	height := m.bodyHeight()
	start := m.cursor[worldsScreen] / max(1, height-2) * max(1, height-2)
	listTitle, detailTitle := "世界列表", "世界详情"
	if m.worldPane {
		detailTitle += " ●"
	} else {
		listTitle += " ●"
	}
	list := pane(listTitle, worlds, left, height, start, "")
	details, _ := m.worldDetailLines(right - 4)
	footer := ""
	if len(details) > height-2 {
		footer = fmt.Sprintf("鼠标滚轮 %d/%d", min(m.worldOffset, max(0, len(details)-height+2))+1, len(details))
	}
	detail := pane(detailTitle, details, right, height, m.worldOffset, footer)
	if m.bodyWidth() < 55 {
		if m.worldPane {
			return detail
		}
		return list
	}
	return lipgloss.JoinHorizontal(lipgloss.Top, list, " ", detail)
}
