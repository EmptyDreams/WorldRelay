package main

import (
	"bytes"
	"strings"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/x/ansi"
)

type memoryTerminal struct{ bytes.Buffer }

func (*memoryTerminal) Close() error { return nil }
func (*memoryTerminal) Fd() uintptr  { return 0 }

// 验证完整帧及跳过未变化行的增量帧都保留真实输入光标，离开表单后恢复隐藏并透传退出序列。
func TestTerminalCursorFollowsRenderedFrames(t *testing.T) {
	terminal := &memoryTerminal{}
	out := &cursorOutput{terminalFile: terminal}
	write := func(data string) string {
		t.Helper()
		terminal.Reset()
		if n, err := out.Write([]byte(data)); err != nil || n != len(data) {
			t.Fatalf("光标输出失败：%d %v", n, err)
		}
		return terminal.String()
	}
	view := write(cursorFrame("frame", 12, 15) + "\x1b[20;H")
	if !strings.HasSuffix(view, ansi.CursorPosition(12, 15)+ansi.ShowCursor) || strings.Contains(view, frameCursorPrefix) {
		t.Fatal("真实光标没有回到输入框")
	}
	view = write("changed input\x1b[20;H")
	if !strings.HasSuffix(view, ansi.CursorPosition(12, 15)+ansi.ShowCursor) {
		t.Fatal("增量帧丢失了光标位置")
	}
	view = write(cursorFrame("list", 0, 0) + "\x1b[20;H")
	if !strings.HasSuffix(view, ansi.HideCursor) {
		t.Fatal("离开输入框后没有隐藏真实光标")
	}
	cleanup := ansi.ShowCursor + "\x1b[?1049l"
	if write(cleanup) != cleanup {
		t.Fatal("干扰了终端退出与光标恢复")
	}
}

// 验证表单真实光标与中文字符、左右编辑及横向滚动后的可见位置对齐。
func TestInputCursorPositionMatchesVisibleText(t *testing.T) {
	m := newModel(newClient(1, time.Second), journal{t.TempDir()}, 0)
	m.Update(tea.WindowSizeMsg{Width: 80, Height: 24})
	m.openForm("abandon", "放弃进度", "输入原因", "")
	m.input.SetValue("中文ab")
	terminal := &memoryTerminal{}
	out := &cursorOutput{terminalFile: terminal}
	assertCursor := func(column int) {
		t.Helper()
		view := m.View()
		row := 0
		for i, line := range strings.Split(ansi.Strip(view), "\n") {
			if strings.Contains(line, "> ") {
				row = i + 1
				break
			}
		}
		if row == 0 {
			t.Fatal("输入框未显示")
		}
		terminal.Reset()
		out.Write([]byte(view + "\x1b[24;H"))
		if !strings.HasSuffix(terminal.String(), ansi.CursorPosition(column, row)+ansi.ShowCursor) {
			t.Fatalf("光标没有定位到输入框第 %d 列", column)
		}
		if strings.Contains(terminal.String(), inputCursorMark) {
			t.Fatal("内部光标标记泄露到了终端")
		}
	}
	assertCursor(11)
	m.input.SetCursor(2)
	assertCursor(9)
	m.input.SetValue(strings.Repeat("中文", 80))
	view := m.View()
	terminal.Reset()
	out.Write([]byte(view + "\x1b[24;H"))
	if out.column < 5 || out.column > m.width-3 {
		t.Fatal("长输入的光标越过了可见输入框")
	}
}
