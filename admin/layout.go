package main

import (
	"encoding/json"
	"fmt"
	"sort"
	"strings"

	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/x/ansi"
)

var borderStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("240"))
var successStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("42"))
var activeTab = lipgloss.NewStyle().Foreground(lipgloss.Color("230")).Background(lipgloss.Color("24")).Bold(true)

func padRight(value string, width int) string {
	return value + strings.Repeat(" ", max(0, width-lipgloss.Width(value)))
}

func fit(value string, width int) string {
	return padRight(ansi.Truncate(value, max(1, width), "…"), width)
}

func between(left, right string, width int) string {
	left = ansi.Truncate(left, max(1, width-lipgloss.Width(right)-2), "…")
	return left + strings.Repeat(" ", max(1, width-lipgloss.Width(left)-lipgloss.Width(right))) + right
}

func rule(left, right, title string, width int) string {
	title = ansi.Truncate(" "+clean(title)+" ", max(1, width-4), "…")
	return borderStyle.Render(left+"─") + accent.Render(title) + borderStyle.Render(strings.Repeat("─", max(0, width-3-lipgloss.Width(title)))+right)
}

func (m model) bodyWidth() int { return max(10, m.width-5) }

func (m model) pageTitle() string {
	switch m.screen {
	case homeScreen:
		return "首页 / 服务概览"
	case worldsScreen:
		return "世界管理 / 共享世界"
	case usersScreen:
		return "用户管理 / 玩家账户"
	case detailScreen:
		return "世界详情 / " + clean(text(m.world, "name"))
	case backupsScreen:
		return "备份 / " + clean(text(m.world, "name"))
	case historyScreen:
		return "历史 / " + clean(text(m.world, "name"))
	case worldModeScreen:
		return "创建共享世界 / 登录模式"
	case formScreen:
		return m.form.title
	case confirmScreen:
		return "确认操作 / " + clean(m.confirmation.Title)
	case operationScreen:
		return "待确认操作"
	case resultScreen:
		return "操作成功"
	}
	return "WorldRelay"
}

func (m model) navigation() string {
	return renderMenu(m.menuItems(), m.menuIndex(), m.width-1, true)
}

func (m model) View() string {
	if m.width < 36 || m.height < 12 {
		return cursorFrame("终端过小，请放大至至少 36 列 × 12 行。\nCtrl+C 退出", 0, 0)
	}
	width := m.width - 1
	endpoint := strings.TrimSuffix(strings.TrimPrefix(m.api.base, "http://"), "/api/v1/")
	right := muted.Render(endpoint)
	if m.width < 72 {
		right = ""
	}
	cursorColumn, cursorRow := 0, 0
	lines := []string{between(accent.Render(" WORLDRELAY  管理终端"), right, width), rule("╭", "╮", m.pageTitle(), width)}
	body := m.bodyLines()
	height := m.bodyHeight()
	start := min(m.scroll, max(0, len(body)-height))
	end := min(len(body), start+height)
	for i := 0; i < height; i++ {
		line := ""
		if start+i < end {
			line = body[start+i]
		}
		lines = append(lines, borderStyle.Render("│")+" "+fit(line, m.bodyWidth())+" "+borderStyle.Render("│"))
	}
	if m.screen == formScreen || m.screen == confirmScreen {
		label := "输入 / Enter 提交 · Esc 取消"
		if m.screen == confirmScreen {
			label = "输入目标名称确认"
		}
		input := m.input.View()
		if at := strings.Index(input, inputCursorMark); at >= 0 {
			cursorColumn, cursorRow = 3+ansi.StringWidth(input[:at]), len(lines)+2
			input = strings.ReplaceAll(input, inputCursorMark, "")
		}
		lines = append(lines, rule("├", "┤", label, width), borderStyle.Render("│")+" "+fit(input, m.bodyWidth())+" "+borderStyle.Render("│"), borderStyle.Render("│")+strings.Repeat(" ", width-2)+borderStyle.Render("│"))
	}
	footer := ""
	if m.isTable() && m.rowCount() > 0 {
		cursor := max(0, min(m.cursor[m.screen], m.rowCount()-1))
		footer = fmt.Sprintf("第 %d/%d 页 · %d/%d 条", cursor/m.pageSize()+1, (m.rowCount()+m.pageSize()-1)/m.pageSize(), cursor+1, m.rowCount())
		if m.rowCount() > m.pageSize() {
			footer = "鼠标滚轮 · " + footer
		}
	} else if len(body) > height {
		footer = fmt.Sprintf("鼠标滚轮 · %d–%d / %d", start+1, end, len(body))
	}
	if footer == "" {
		lines = append(lines, borderStyle.Render("╰"+strings.Repeat("─", width-2)+"╯"))
	} else {
		lines = append(lines, rule("╰", "╯", footer, width))
	}
	if m.notice != "" && m.screen != homeScreen {
		lines = append(lines, fit(warningStyle.Render("▲ "+clean(m.notice)), width))
	}
	if m.screen != formScreen && m.screen != confirmScreen {
		lines = append(lines, fit(m.navigation(), width))
	}
	lines = append(lines, fit(m.globalHelp(), width))
	return cursorFrame(strings.Join(lines, "\n"), cursorColumn, cursorRow)
}

func (m model) bodyLines() []string {
	return strings.Split(ansi.Hardwrap(m.content(), m.bodyWidth(), true), "\n")
}

// 卡片内部自行换行，所有边框使用终端显示宽度计算，中文不挤歪相邻栏。
func card(title string, width int, lines []string, minRows int) string {
	return flexCard(title, width, lines, minRows, false)
}

func flexCard(title string, width int, lines []string, minRows int, stretch bool) string {
	inner := max(1, width-4)
	groups := make([][]string, len(lines))
	count := 0
	for i, line := range lines {
		groups[i] = strings.Split(ansi.Hardwrap(line, inner, true), "\n")
		count += len(groups[i])
	}
	extra := max(0, minRows-count)
	var content []string
	for i, group := range groups {
		content = append(content, group...)
		if stretch && len(groups) > 1 && i < len(groups)-1 {
			gaps := len(groups) - 1
			before := (i*extra + gaps/2) / gaps
			after := ((i+1)*extra + gaps/2) / gaps
			for padding := before; padding < after; padding++ {
				content = append(content, "")
			}
		}
	}
	for len(content) < minRows {
		content = append(content, "")
	}
	out := []string{rule("╭", "╮", title, width)}
	for _, line := range content {
		out = append(out, borderStyle.Render("│")+" "+fit(line, inner)+" "+borderStyle.Render("│"))
	}
	out = append(out, borderStyle.Render("╰"+strings.Repeat("─", max(0, width-2))+"╯"))
	return strings.Join(out, "\n")
}

func wrappedRows(lines []string, inner int) int {
	total := 0
	for _, line := range lines {
		total += len(strings.Split(ansi.Hardwrap(line, max(1, inner), true), "\n"))
	}
	return total
}

func columns(leftTitle string, left []string, rightTitle string, right []string, width int) string {
	if width < 68 {
		return card(leftTitle, width, left, 0) + "\n" + card(rightTitle, width, right, 0)
	}
	leftWidth := (width - 1) / 2
	rightWidth := width - leftWidth - 1
	count := max(wrappedRows(left, leftWidth-4), wrappedRows(right, rightWidth-4))
	return lipgloss.JoinHorizontal(lipgloss.Top, card(leftTitle, leftWidth, left, count), " ", card(rightTitle, rightWidth, right, count))
}

func metric(label, value string) string {
	return muted.Render(padRight(label, 12)) + " " + clean(value)
}

func progress(used, total int64, width int) string {
	if total <= 0 {
		return muted.Render("暂无使用率数据")
	}
	ratio := max(0.0, min(1.0, float64(used)/float64(total)))
	cells := max(4, width-8)
	fill := int(ratio*float64(cells) + 0.5)
	return accent.Render(strings.Repeat("━", fill)) + borderStyle.Render(strings.Repeat("─", cells-fill)) + muted.Render(fmt.Sprintf(" %3.0f%%", ratio*100))
}

func (m model) worldDetail(w, tasks object) string {
	width := m.bodyWidth()
	owner := obj(w, "owner")
	basic := []string{field("世界名称", text(w, "name")), field("登录模式", worldLoginMode(w)), field("世界 ID", text(w, "worldId")), field("状态", phase(text(w, "status"))+" · 玩家 "+text(w, "playerCount")), field("权威 / 基础版本", text(w, "committedVersion")+" / "+text(w, "baseVersion")), field("最近提交", localTime(text(w, "lastCommittedAt")))}
	ownership := []string{metric("持有者", text(owner, "username")), metric("当前任期", text(w, "epoch")), metric("持久阶段", text(w, "phase")), metric("最近联系", localTime(text(w, "lastContactAt")))}
	status := []string{metric("在线", text(w, "online")), metric("存储不可用", text(w, "unavailable")), metric("正在恢复", text(w, "restoring"))}
	if tasks != nil {
		status = append(status, metric("排队 / 运行", text(tasks, "queued")+" / "+text(tasks, "running")), metric("结果缓存", text(tasks, "cached")))
	}
	content := card("世界与存档", width, basic, 0) + "\n" + columns("当前持有关系", ownership, "运行与同步", status, width)
	// 较长的设备、会话和端点信息单列展示；常用状态优先在首屏完整显示。
	var identifiers []string
	if owner != nil {
		identifiers = append(identifiers, field("设备 ID", text(owner, "deviceId")), field("会话 ID", text(owner, "sessionId")), field("请求 ID", text(owner, "requestId")))
	}
	if endpoint := obj(w, "endpoint"); endpoint != nil {
		data, _ := json.Marshal(endpoint)
		identifiers = append(identifiers, field("登记端点", string(data)))
	}
	if len(identifiers) > 0 {
		content += "\n" + card("会话与连接标识", width, identifiers, 0)
	}
	return content
}

func (m model) content() string {
	width := m.bodyWidth()
	switch m.screen {
	case homeScreen:
		return m.overview()
	case worldsScreen:
		return m.worldWorkspace()
	case usersScreen:
		var data [][]string
		for _, u := range m.users {
			status := "○ 禁用"
			if flagValue(u, "enabled") {
				status = "● 启用"
			}
			data = append(data, []string{text(u, "username"), status, text(u, "transferAuthorizations")})
		}
		return m.table([]string{"玩家名称", "状态", "传输授权"}, data, []int{5, 3, 3})
	case detailScreen:
		return m.worldDetail(m.world, obj(m.detail, "tasks"))
	case backupsScreen:
		var data [][]string
		for _, b := range m.backups {
			current := ""
			if flagValue(b, "current") {
				current = "● 当前"
			}
			if b["restoredFromVersion"] != nil {
				current += " · 恢复自 #" + text(b, "restoredFromVersion")
			}
			data = append(data, []string{text(b, "version"), localTime(text(b, "committedAt")), current})
		}
		return m.table([]string{"版本", "提交时间", "标记"}, data, []int{2, 6, 2})
	case historyScreen:
		var data [][]string
		for _, e := range m.events {
			data = append(data, []string{text(e, "epoch"), eventName(text(e, "type")), text(e, "username"), text(e, "version"), localTime(text(e, "at"))})
		}
		headers := []string{"任期", "事件", "操作者", "版本", "时间"}
		weights := []int{1, 3, 3, 1, 5}
		if width < 62 {
			headers = []string{"事件", "任期", "版本"}
			weights = []int{4, 2, 2}
			for i, row := range data {
				data[i] = []string{row[1], row[0], row[3]}
			}
		}
		content := m.table(headers, data, weights)
		if len(m.events) > 0 && m.bodyHeight() >= 9 {
			e := m.events[m.cursor[historyScreen]]
			content += "\n" + borderStyle.Render(strings.Repeat("─", width)) + "\n" + fit(field("所选时间", localTime(text(e, "at"))), width) + "\n" + fit(field("版本 / 来源", text(e, "version")+" / "+text(e, "sourceVersion")), width) + "\n" + fit(field("原因", text(e, "reason")), width)
		}
		return content
	case worldModeScreen:
		return card("世界名称", width, []string{clean(m.newWorldName)}, 0) + "\n" + card("登录模式 · 创建后不可修改", width,
			[]string{"仅允许正版账号：使用正版身份验证。", "允许离线账号：所有玩家统一使用离线身份，房主交接时保持玩家数据一致。", "两种模式都需要 WorldRelay 账户和 Token。", "请在下方选择模式并按 Enter 创建世界。"}, 0)
	case formScreen:
		return card("输入说明", width, []string{m.form.hint}, 0)
	case confirmScreen:
		c := m.confirmation
		return card("! 操作影响", width, []string{warningStyle.Render(clean(c.warning))}, 0) + "\n" + card("操作目标", width, m.operationFields(&c.operation), 0) + "\n" + field("确认名称", c.Target)
	case operationScreen:
		if m.pending == nil {
			return card("操作已拒绝", width, []string{"待确认记录已清除。", "返回列表刷新后，可重新预览。"}, 0)
		}
		return card("已确认 / "+clean(m.pending.Title), width, m.operationFields(m.pending), 0) + "\n" + card("重试与退出", width, []string{"选择下方重试按钮，使用同一个操作编号查询或重试。", "原请求已保存在本机，退出后再次启动可继续。", "恢复返回 202 时自动查询；关闭工具不取消任务。"}, 0)
	case resultScreen:
		if token, ok := m.result["token"].(string); ok {
			value := muted.Render("（已隐藏，选择下方显示按钮查看）")
			if m.reveal {
				value = clean(token)
			}
			return card("账户操作成功", width, []string{field("玩家", text(m.result, "username"))}, 0) + "\n" + card("Token", width, []string{value}, 0) + "\n" + muted.Render("请及时复制并妥善保存；离开此页后清除，Token 不写入文件。")
		}
		if w := obj(m.result, "world"); w != nil {
			return successStyle.Render("✓ 世界已更新") + "\n" + m.worldDetail(w, obj(m.result, "tasks"))
		}
		return card("✓ 操作完成", width, resultFields(m.result), 0)
	}
	return ""
}

func eventName(value string) string {
	if name, ok := map[string]string{"COMMIT": "提交存档", "RESTORE": "恢复备份", "ABANDON": "放弃进度", "CANCEL_CREATION": "取消创建"}[value]; ok {
		return name
	}
	return clean(value)
}

func (m model) operationFields(op *operation) []string {
	lines := []string{field("目标", op.Target)}
	for _, item := range []struct{ key, label string }{{"worldId", "世界 ID"}, {"username", "玩家"}, {"ownerUsername", "当前房主"}, {"expectedVersion", "当前权威版本"}, {"backupVersion", "备份版本"}, {"expectedEpoch", "当前任期"}, {"expectedPhase", "当前阶段"}, {"lastCommittedAt", "最后提交时间"}, {"reason", "放弃原因"}, {"restoreId", "恢复编号"}, {"abandonId", "放弃编号"}, {"deleteId", "删除编号"}} {
		if _, ok := op.Body[item.key]; ok {
			value := text(op.Body, item.key)
			if item.key == "lastCommittedAt" {
				value = localTime(value)
			}
			lines = append(lines, field(item.label, value))
		}
	}
	return lines
}

func resultFields(result object) []string {
	labels := map[string]string{"worldId": "世界 ID", "name": "世界名称", "status": "状态", "username": "玩家", "enabled": "账户启用", "transferAuthorizations": "传输授权", "version": "新版本", "previousVersion": "原版本", "backupVersion": "备份版本", "committedVersion": "权威版本", "epoch": "当前任期", "revokedEpoch": "撤销的任期", "previousOwner": "原持有者", "deleted": "已删除", "deletedAt": "删除时间", "purgeAfter": "最早回收时间", "committedAt": "提交时间", "lastCommittedAt": "最近提交", "abandonedAt": "放弃时间", "restoreId": "恢复编号", "deleteId": "删除编号", "abandonId": "放弃编号", "hostName": "房主", "playerCount": "玩家数", "endpoint": "连接地址"}
	order := []string{"name", "worldId", "username", "status", "enabled", "previousOwner", "revokedEpoch", "epoch", "previousVersion", "backupVersion", "version", "committedVersion", "deleted", "committedAt", "lastCommittedAt", "deletedAt", "purgeAfter", "abandonedAt", "restoreId", "deleteId", "abandonId", "hostName", "playerCount", "endpoint", "transferAuthorizations"}
	seen := map[string]bool{}
	var lines []string
	add := func(key string) {
		value, ok := result[key]
		if !ok {
			return
		}
		seen[key] = true
		label := labels[key]
		if label == "" {
			label = key
		}
		display := text(result, key)
		if display == "—" && value != nil {
			encoded, _ := json.Marshal(value)
			display = string(encoded)
		}
		if key == "status" {
			display = phase(display)
		}
		if strings.HasSuffix(key, "At") || key == "purgeAfter" {
			display = localTime(display)
		}
		lines = append(lines, field(label, display))
	}
	for _, key := range order {
		add(key)
	}
	var rest []string
	for key := range result {
		if !seen[key] {
			rest = append(rest, key)
		}
	}
	sort.Strings(rest)
	for _, key := range rest {
		add(key)
	}
	return lines
}

func (m model) table(headers []string, data [][]string, weights []int) string {
	width := m.bodyWidth()
	available := width - 2 - 3*(len(headers)-1)
	sizes := make([]int, len(headers))
	for i, label := range headers {
		sizes[i] = lipgloss.Width(label)
		available -= sizes[i]
	}
	for i, label := range headers {
		if (label == "时间" || label == "提交时间") && available >= 19-sizes[i] {
			available -= 19 - sizes[i]
			sizes[i] = 19
		}
	}
	weightSum := 0
	for _, weight := range weights {
		weightSum += weight
	}
	extra := available
	for i, weight := range weights {
		share := max(0, extra*weight/weightSum)
		sizes[i] += share
		available -= share
	}
	for i := 0; available > 0; i = (i + 1) % len(sizes) {
		sizes[i]++
		available--
	}
	format := func(values []string, separator string) string {
		var cells []string
		for i, value := range values {
			cells = append(cells, fit(clean(value), sizes[i]))
		}
		return strings.Join(cells, separator)
	}
	var segments []string
	for _, size := range sizes {
		segments = append(segments, strings.Repeat("─", size))
	}
	separator := borderStyle.Render(" │ ")
	lines := []string{muted.Render("  " + format(headers, separator)), borderStyle.Render("──" + strings.Join(segments, "─┼─"))}
	if len(data) == 0 {
		empty := "  暂无记录"
		if m.screen == worldsScreen || m.screen == usersScreen {
			empty += " · 选择下方创建按钮"
		}
		return strings.Join(append(lines, muted.Render(empty)), "\n")
	}
	cursor := max(0, min(m.cursor[m.screen], len(data)-1))
	start := cursor / m.pageSize() * m.pageSize()
	end := min(len(data), start+m.pageSize())
	for i := start; i < end; i++ {
		line := "  " + format(data[i], separator)
		if i == cursor {
			line = selected.Render("› " + format(data[i], " │ "))
		}
		lines = append(lines, line)
	}
	return strings.Join(lines, "\n")
}

func (m model) globalHelp() string {
	hint := "[←→/AD] 按钮  [Enter] 选择"
	switch m.screen {
	case formScreen, confirmScreen:
		if m.screen == confirmScreen {
			return muted.Render("[Enter] 确认  [Esc] 取消")
		}
		return muted.Render("[Enter] 提交  [Esc] 取消")
	case worldsScreen:
		if m.worldPane {
			hint += "  [↑↓] 备份  [空格] 列表"
		} else {
			hint += "  [↑↓] 世界  [空格] 详情"
		}
	case usersScreen, backupsScreen, historyScreen:
		hint += "  [↑↓] 选择条目"
	}
	if m.screen != homeScreen {
		hint += "  [Esc] 返回"
	}
	return muted.Render(hint)
}
