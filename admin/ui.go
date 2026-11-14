package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"

	"github.com/charmbracelet/bubbles/cursor"
	"github.com/charmbracelet/bubbles/textinput"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

type screen int

const (
	homeScreen screen = iota
	worldsScreen
	usersScreen
	detailScreen
	backupsScreen
	historyScreen
	formScreen
	confirmScreen
	resultScreen
	operationScreen
	worldModeScreen
)

type replyMsg struct {
	serial   uint64
	kind     string
	response response
	err      error
}
type tickMsg struct{}
type pollMsg struct{ serial uint64 }
type form struct {
	kind, title, hint string
	back              screen
}
type confirmation struct {
	operation
	warning string
	back    screen
}

type model struct {
	api                                     *client
	store                                   journal
	refresh                                 time.Duration
	screen                                  screen
	width, height, scroll                   int
	cursor                                  map[screen]int
	status, detail, backupInfo, historyInfo object
	worlds, users, backups, events          []object
	world                                   object
	input                                   textinput.Model
	form                                    form
	confirmation                            *confirmation
	pending                                 *operation
	result                                  object
	resultBack                              screen
	reason, notice                          string
	busy                                    bool
	serial                                  uint64
	worldPane                               bool
	worldButtons                            [2]int
	worldOffset, worldBackup                int
	worldDetailSeq                          uint64
	worldLoading                            bool
	worldError                              string
	operationBack                           screen
	newWorldName                            string
	pageButtons                             map[screen]int
}

func newModel(api *client, store journal, refresh time.Duration) *model {
	input := textinput.New()
	input.CharLimit = 256
	input.Prompt = "> "
	input.Cursor.SetMode(cursor.CursorHide)
	input.Cursor.TextStyle = lipgloss.NewStyle().Transform(func(value string) string { return inputCursorMark + value })
	input.KeyMap.Paste.SetEnabled(false) // 使用终端原生粘贴，不调用服务器上的 xclip/xsel。
	return &model{api: api, store: store, refresh: refresh, width: 100, height: 30, cursor: map[screen]int{}, pageButtons: map[screen]int{}, input: input, operationBack: worldsScreen}
}

func (m model) Init() tea.Cmd {
	// Init 不更改模型状态；首次结果使用 serial=0，避免初始化阶段漏收响应。
	return tea.Batch(func() tea.Msg {
		r, err := m.api.call(context.Background(), "status", nil)
		return replyMsg{0, "home", r, err}
	}, m.nextTick())
}

func (m model) nextTick() tea.Cmd {
	if m.refresh == 0 {
		return nil
	}
	return tea.Tick(m.refresh, func(time.Time) tea.Msg { return tickMsg{} })
}

func (m *model) request(path string, body object, kind string) tea.Cmd {
	m.busy = true
	m.serial++
	serial, api := m.serial, m.api
	return func() tea.Msg {
		r, err := api.call(context.Background(), path, body)
		return replyMsg{serial, kind, r, err}
	}
}

func (m *model) load() tea.Cmd {
	switch m.screen {
	case homeScreen:
		return m.request("status", nil, "home")
	case worldsScreen:
		return m.request("worlds", nil, "worlds")
	case usersScreen:
		return m.request("users", nil, "users")
	case detailScreen:
		return m.request("worlds/detail", object{"worldId": m.world["worldId"]}, "detail")
	case backupsScreen:
		return m.request("worlds/backups", object{"worldId": m.world["worldId"]}, "backups")
	case historyScreen:
		return m.request("worlds/history", object{"worldId": m.world["worldId"], "limit": 50}, "history")
	}
	return nil
}

func (m *model) navigate(to screen) tea.Cmd {
	selectingToken := m.showingToken()
	m.screen, m.scroll, m.result, m.notice = to, 0, nil, ""
	cmd := m.load()
	if selectingToken {
		return tea.Batch(tea.EnableMouseCellMotion, cmd)
	}
	return cmd
}

func (m model) showingToken() bool {
	token, ok := m.result["token"].(string)
	return m.screen == resultScreen && ok && token != ""
}

func (m *model) openForm(kind, title, hint, value string) tea.Cmd {
	m.form = form{kind, title, hint, m.screen}
	m.screen, m.scroll, m.notice = formScreen, 0, ""
	m.input.SetValue(value)
	m.input.CursorEnd()
	return m.input.Focus()
}

func (m *model) ask(op operation, warning string, back screen) tea.Cmd {
	m.confirmation = &confirmation{op, warning, back}
	m.screen, m.scroll, m.notice = confirmScreen, 0, ""
	m.input.SetValue("")
	return m.input.Focus()
}

func (m *model) canMutate() bool {
	if m.pending != nil {
		m.screen, m.scroll = operationScreen, 0
		m.notice = "请先查询或重试上次已确认的操作；使用同一请求，不会生成新的操作编号。"
		return false
	}
	return true
}

func (m *model) Update(message tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := message.(type) {
	case tea.WindowSizeMsg:
		m.width, m.height = msg.Width, msg.Height
		m.input.Width = max(10, min(msg.Width-8, 80))
		return m, nil
	case replyMsg:
		if msg.serial != m.serial {
			return m, nil
		}
		m.busy = false
		if msg.err != nil {
			m.notice = clean(msg.err.Error())
			if msg.kind == "mutation" {
				m.notice += "；未自动重试。创建结果请返回列表核实；未收到 Token 时可显式重置。"
			}
			if msg.kind == "execute" && m.pending != nil {
				m.screen = operationScreen
				var apiErr *apiError
				if errors.As(msg.err, &apiErr) && apiErr.status >= 400 && apiErr.status < 500 {
					if err := m.store.clear(); err != nil {
						m.notice += "；清理操作记录失败：" + clean(err.Error())
					} else {
						m.pending = nil
					}
				}
			}
			return m, nil
		}
		m.notice = ""
		r := msg.response.body
		switch msg.kind {
		case "home":
			m.status = r
		case "worlds":
			previous := text(m.selectedWorld(), "worldId")
			m.worlds = rows(r, "worlds")
			for i, world := range m.worlds {
				if text(world, "worldId") == previous {
					m.cursor[worldsScreen] = i
					break
				}
			}
			m.clampCursor()
			return m, m.loadWorldSelection()
		case "users":
			m.users = rows(r, "users")
		case "detail":
			m.detail, m.world = r, obj(r, "world")
		case "backups":
			m.backupInfo, m.backups = r, rows(r, "backups")
		case "history":
			m.historyInfo, m.events = r, rows(r, "events")
			m.cursor[historyScreen] = 0
		case "history-next":
			m.historyInfo = r
			m.events = append(m.events, rows(r, "events")...)
		case "preview-delete", "preview-restore", "preview-abandon", "preview-backup-delete":
			kind := strings.TrimPrefix(msg.kind, "preview-")
			title, warning := "恢复备份", "将所选备份发布为新版本，当前版本仍按备份策略保留。"
			if kind == "delete" {
				title, warning = "删除世界", "世界将从列表撤下，服务端回收副本默认保留 24 小时（以服务端配置为准）。"
			}
			if kind == "backup-delete" {
				title, warning = "删除备份", "永久删除所选历史快照；世界当前权威版本保持不变。"
			}
			if kind == "abandon" {
				title, warning = "放弃未上传进度", "撤销当前房主的所有权。未上传进度不再被接受，其他玩家可从最后提交版本接管。"
				r["reason"] = m.reason
			}
			target := text(m.world, "name")
			if kind == "delete" {
				target = text(r, "expectedName")
			}
			back := m.screen
			if back == formScreen {
				back = m.form.back
			}
			return m, m.ask(operation{"worlds/" + kind, title, target, r}, warning, back)
		case "execute":
			if msg.response.status == 202 && m.pending != nil {
				serial := m.serial
				return m, tea.Tick(time.Second, func(time.Time) tea.Msg { return pollMsg{serial} })
			}
			if m.pending != nil {
				if err := m.store.clear(); err != nil {
					m.notice = "服务端已成功，但操作记录未清除；重试原请求可再次核实：" + clean(err.Error())
					return m, nil
				}
				m.world = object{"worldId": m.pending.Body["worldId"], "name": m.pending.Target}
				m.resultBack = m.operationBack
				m.pending = nil
			}
			m.screen, m.result, m.scroll = resultScreen, r, 0
		case "mutation":
			if m.screen == formScreen && m.form.kind == "rename" {
				m.world, m.worldPane = obj(r, "world"), false
				m.input.Blur()
				return m, m.navigate(worldsScreen)
			}
			m.resultBack = m.screen
			if m.screen == worldModeScreen {
				m.resultBack = worldsScreen
			}
			if m.screen == formScreen {
				m.resultBack = m.form.back
			}
			if m.screen == confirmScreen {
				m.resultBack = m.confirmation.back
			}
			m.screen, m.result, m.scroll = resultScreen, r, 0
		}
		m.clampCursor()
		if m.showingToken() {
			// 让终端接管拖选；此页不自动刷新，避免选中的 Token 被重绘打断。
			return m, tea.DisableMouse
		}
		return m, nil
	case worldDetailMsg:
		if m.screen != worldsScreen || msg.sequence != m.worldDetailSeq || msg.worldID != text(m.selectedWorld(), "worldId") {
			return m, nil
		}
		m.worldLoading = false
		if msg.err != nil {
			m.worldError = clean(msg.err.Error())
			return m, nil
		}
		m.worldError, m.detail, m.backupInfo = "", msg.detail, msg.backups
		world := obj(msg.detail, "world")
		if text(world, "worldId") != msg.worldID {
			m.worldError = "世界详情响应与所选世界不一致，请刷新后重试。"
			return m, nil
		}
		m.world = world
		previousBackup := ""
		if m.worldBackup < len(m.backups) {
			previousBackup = text(m.backups[m.worldBackup], "version")
		}
		m.backups = rows(msg.backups, "backups")
		m.worldBackup = max(0, min(m.worldBackup, len(m.backups)-1))
		for i, backup := range m.backups {
			if text(backup, "version") == previousBackup {
				m.worldBackup = i
				break
			}
		}
		m.normalizeWorldButton()
		return m, nil
	case tickMsg:
		var cmd tea.Cmd
		if !m.busy && (m.screen == homeScreen || m.screen == usersScreen) {
			cmd = m.load()
		}
		return m, tea.Batch(cmd, m.nextTick())
	case pollMsg:
		if !m.busy && msg.serial == m.serial && m.pending != nil {
			return m, m.request(m.pending.Path, m.pending.Body, "execute")
		}
		return m, nil
	case tea.MouseMsg:
		if msg.Action != tea.MouseActionRelease && msg.X >= 2 && msg.X < m.width-2 && msg.Y >= 2 && msg.Y < 2+m.bodyHeight() {
			if m.screen == worldsScreen {
				return m, m.worldWheel(msg)
			}
			switch msg.Button {
			case tea.MouseButtonWheelUp:
				m.scrollContent(-3)
			case tea.MouseButtonWheelDown:
				m.scrollContent(3)
			}
		}
		return m, nil
	case tea.KeyMsg:
		key := msg.String()
		if msg.Type == tea.KeyCtrlC {
			return m, tea.Quit
		}
		if m.screen == formScreen || m.screen == confirmScreen {
			if msg.Type == tea.KeyEsc {
				return m, m.returnToParent()
			}
			if m.busy {
				return m, nil
			}
			if msg.Type == tea.KeyEnter {
				return m, m.submit()
			}
			var cmd tea.Cmd
			m.input, cmd = m.input.Update(msg)
			return m, cmd
		}
		if m.screen == worldsScreen {
			return m, m.worldKey(key)
		}
		if m.screen == homeScreen {
			cmd, _ := m.homeKey(key)
			return m, cmd
		}
		return m, m.pageMenuKey(key)
	}
	return m, nil
}

func (m *model) submit() tea.Cmd {
	value := m.input.Value()
	if m.screen == confirmScreen {
		c := m.confirmation
		if value != c.Target {
			m.notice = "输入必须与目标名称完全一致。"
			return nil
		}
		if strings.HasPrefix(c.Path, "worlds/") {
			body := object{}
			for k, v := range c.Body {
				body[k] = v
			}
			body["confirm"] = true
			op := &operation{c.Path, c.Title, c.Target, body}
			if err := m.store.save(op); err != nil {
				m.notice = "未发送请求，保存重试记录失败：" + clean(err.Error())
				return nil
			}
			m.pending = op
			m.operationBack = c.back
			m.screen, m.scroll = operationScreen, 0
			m.confirmation = nil
			m.input.Blur()
			return m.request(op.Path, op.Body, "execute")
		}
		return m.request(c.Path, c.Body, "mutation")
	}
	if strings.TrimSpace(value) == "" {
		m.notice = "请输入内容。"
		return nil
	}
	switch m.form.kind {
	case "create-world":
		m.newWorldName = value
		m.input.Blur()
		m.screen, m.scroll = worldModeScreen, 0
		m.pageButtons[worldModeScreen] = 0
		return nil
	case "create-user":
		return m.request("register", object{"username": value}, "mutation")
	case "rename":
		return m.request("worlds/rename", object{"worldId": m.world["worldId"], "expectedName": m.world["name"], "name": value}, "mutation")
	case "abandon":
		m.reason = value
		return m.request("worlds/abandon-preview", object{"worldId": m.world["worldId"]}, "preview-abandon")
	}
	return nil
}

var accent = lipgloss.NewStyle().Foreground(lipgloss.Color("39")).Bold(true)
var muted = lipgloss.NewStyle().Foreground(lipgloss.Color("245"))
var selected = lipgloss.NewStyle().Foreground(lipgloss.Color("230")).Background(lipgloss.Color("24"))
var warningStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("214"))

func (m model) bodyHeight() int {
	reserve := 5
	if m.screen != homeScreen && m.notice != "" {
		reserve++
	}
	if m.screen == formScreen || m.screen == confirmScreen {
		reserve += 2
	}
	return max(1, m.height-reserve)
}
func (m model) pageSize() int {
	reserve := 2
	if m.screen == historyScreen && m.bodyHeight() >= 9 {
		reserve = 6
	}
	return max(1, m.bodyHeight()-reserve)
}

func (m *model) scrollContent(delta int) {
	if m.isTable() && m.rowCount() > m.pageSize() {
		m.cursor[m.screen] += delta
		m.clampCursor()
		return
	}
	limit := max(0, len(m.bodyLines())-m.bodyHeight())
	m.scroll = max(0, min(min(m.scroll, limit)+delta, limit))
}

func (m model) isTable() bool {
	return m.screen == worldsScreen || m.screen == usersScreen || m.screen == backupsScreen || m.screen == historyScreen
}
func (m model) rowCount() int {
	switch m.screen {
	case worldsScreen:
		return len(m.worlds)
	case usersScreen:
		return len(m.users)
	case backupsScreen:
		return len(m.backups)
	case historyScreen:
		return len(m.events)
	}
	return 0
}
func (m *model) clampCursor() {
	if m.screen == homeScreen {
		m.cursor[homeScreen] = max(0, min(m.cursor[homeScreen], len(m.homeMenu())-1))
		return
	}
	m.cursor[m.screen] = max(0, min(m.cursor[m.screen], max(0, m.rowCount()-1)))
}

func clean(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || unicode.In(r, unicode.Cf) {
			return -1
		}
		return r
	}, s)
}
func field(label, value string) string { return muted.Render(padRight(label, 16)) + " " + clean(value) }
func localTime(value string) string {
	t, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return clean(value)
	}
	return t.Local().Format("2006-01-02 15:04:05")
}
func byteSize(v int64) string {
	if v < 1024 {
		return fmt.Sprintf("%d B", v)
	}
	units := []string{"KiB", "MiB", "GiB", "TiB"}
	f := float64(v) / 1024
	i := 0
	for f >= 1024 && i < len(units)-1 {
		f /= 1024
		i++
	}
	return fmt.Sprintf("%.1f %s", f, units[i])
}
func runtimeText(ms int64) string {
	seconds := ms / 1000
	return fmt.Sprintf("%d天 %02d:%02d:%02d", seconds/86400, seconds/3600%24, seconds/60%60, seconds%60)
}
func phase(value string) string {
	if s, ok := map[string]string{"FREE": "空闲", "RUNNING": "运行中", "RECOVERY_REQUIRED": "等待原房主恢复", "OWNED_OFFLINE": "持有者离线", "STARTING": "启动中", "STOPPING": "停止中", "COMMITTING": "同步中"}[value]; ok {
		return s
	}
	return clean(value)
}
