package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/x/ansi"
)

// 验证三段占用不重复相加、跨列位置正确，以及 Native 堆上限与窄屏指标仍可读。
func TestResourceBarsAndResponsiveOverview(t *testing.T) {
	bar := ansi.Strip(resourceBar(1000, 600, 200, 20))
	if bar != strings.Repeat("█", 4)+strings.Repeat("▓", 8)+strings.Repeat("░", 8) {
		t.Fatalf("占用条比例错误：%s", bar)
	}
	if got := ansi.Strip(resourceBar(100, 120, 110, 20)); got != strings.Repeat("█", 20) {
		t.Fatalf("采样偏差未限制在系统总量内：%s", got)
	}
	if got := ansi.Strip(resourceBar(100, -1, 0, 20)); got != "暂无使用率数据" {
		t.Fatal("未知占用被当成零占用")
	}
	m := newModel(newClient(1, time.Second), journal{t.TempDir()}, 0)
	m.status = object{
		"version": "fixture", "nativeImage": true,
		"heap": object{"usedBytes": json.Number("33554432"), "committedBytes": nil, "maxBytes": json.Number("536870912")},
		"system": object{
			"cpu":    object{"systemUsagePermille": json.Number("600"), "processUsagePermille": json.Number("200")},
			"memory": object{"totalBytes": json.Number("1073741824"), "availableBytes": json.Number("536870912"), "processRssBytes": json.Number("268435456")},
		},
	}
	m.Update(tea.WindowSizeMsg{Width: 120, Height: 30})
	lines := strings.Split(ansi.Strip(m.overview()), "\n")
	positions := map[string]int{}
	for i, line := range lines {
		for _, title := range []string{"运行信息", "CPU", "磁盘 ·", "内存", "网络 ·"} {
			if at := strings.Index(line, "╭─ "+title); at >= 0 {
				positions[title] = i
				if (title == "CPU" || title == "内存") && at != 0 {
					t.Fatal("CPU 与内存未从左侧开始")
				}
				if title == "磁盘 ·" || title == "网络 ·" {
					if ansi.StringWidth(line[:at]) != 78 {
						t.Fatalf("右侧卡片没有留出两列宽度：%s", line)
					}
				}
			}
		}
	}
	if len(positions) != 5 || positions["CPU"] != positions["磁盘 ·"] || positions["内存"] != positions["网络 ·"] || positions["运行信息"] >= positions["CPU"] || positions["CPU"] >= positions["内存"] {
		t.Fatalf("跨列布局顺序错误：%v", positions)
	}
	for _, width := range []int{120, 100, 60} {
		m.Update(tea.WindowSizeMsg{Width: width, Height: 30})
		view := ansi.Strip(m.overview())
		if !strings.Contains(view, "堆上限") || !strings.Contains(view, "512.0 MiB") || !strings.Contains(view, "32.0 MiB") {
			t.Fatal("Native 堆用量与上限没有完整显示")
		}
		if strings.Contains(view, "█") != (width == 120) {
			t.Fatal("进度条未随三列布局切换")
		}
	}
}

// 验证滚轮仅滚动内容区、到达首尾后停止，且列表与确认输入不会被误触发。
func TestMouseWheelScrollsOverflow(t *testing.T) {
	m := newModel(newClient(1, time.Second), journal{t.TempDir()}, 0)
	m.status = object{"version": "fixture"}
	m.Update(tea.WindowSizeMsg{Width: 60, Height: 20})
	wheel := func(button tea.MouseButton, x, y int) {
		t.Helper()
		_, cmd := m.Update(tea.MouseMsg{Button: button, Action: tea.MouseActionPress, X: x, Y: y})
		if cmd != nil && m.screen != worldsScreen {
			t.Fatal("滚轮触发了后台操作")
		}
	}
	wheel(tea.MouseButtonWheelDown, 5, 4)
	if m.scroll != 3 || m.cursor[homeScreen] != 0 {
		t.Fatal("滚轮没有滚动概览或改变了菜单焦点")
	}
	wheel(tea.MouseButtonWheelDown, 5, m.height-1)
	if m.scroll != 3 {
		t.Fatal("底部菜单区域响应了内容滚轮")
	}
	for i := 0; i < 100; i++ {
		wheel(tea.MouseButtonWheelDown, 5, 4)
	}
	if m.scroll != len(m.bodyLines())-m.bodyHeight() || !strings.Contains(ansi.Strip(m.View()), "累计发送") {
		t.Fatal("滚轮未到达内容末尾")
	}
	for i := 0; i < 100; i++ {
		wheel(tea.MouseButtonWheelUp, 5, 4)
	}
	if m.scroll != 0 {
		t.Fatal("滚轮无法回到内容开头")
	}
	m.screen = worldsScreen
	for i := 0; i < 30; i++ {
		m.worlds = append(m.worlds, object{"name": "测试世界"})
	}
	wheel(tea.MouseButtonWheelDown, 5, 4)
	if m.cursor[worldsScreen] != 3 || m.screen != worldsScreen {
		t.Fatal("列表滚轮没有移动选择或误打开了详情")
	}
	m.ask(operation{"worlds/delete", "删除世界", "测试世界", object{}}, strings.Repeat("确认操作影响", 50), worldsScreen)
	m.input.SetValue("尚未确认")
	wheel(tea.MouseButtonWheelDown, 5, 4)
	if m.scroll != 3 || m.input.Value() != "尚未确认" || !strings.Contains(ansi.Strip(m.View()), "尚未确认") {
		t.Fatal("确认预览滚轮影响了固定输入框")
	}
	m.screen, m.scroll = homeScreen, 0
	m.Update(tea.WindowSizeMsg{Width: 120, Height: 80})
	wheel(tea.MouseButtonWheelDown, 5, 4)
	if m.scroll != 0 {
		t.Fatal("无需滚动的内容被移动")
	}
}
