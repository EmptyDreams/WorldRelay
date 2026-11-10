package main

import (
	"io"
	"strconv"
	"strings"
	"sync"

	"github.com/charmbracelet/x/ansi"
)

const inputCursorMark = "\x1b]777;worldrelay-input-cursor\a"
const frameCursorPrefix = "\x1b]777;worldrelay-frame-cursor="

type terminalFile interface {
	io.ReadWriteCloser
	Fd() uintptr
}

// 保留终端文件接口和窗口尺寸检测；将每帧的真实光标放回输入框，供本地输入法定位。
type cursorOutput struct {
	terminalFile
	mu          sync.Mutex
	column, row int
}

func cursorFrame(view string, column, row int) string {
	return view + frameCursorPrefix + strconv.Itoa(column) + "," + strconv.Itoa(row) + "\a"
}

func (w *cursorOutput) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	data := string(p)
	for {
		start := strings.Index(data, frameCursorPrefix)
		if start < 0 {
			break
		}
		payload := start + len(frameCursorPrefix)
		end := strings.IndexByte(data[payload:], '\a')
		if end < 0 {
			break
		}
		end += payload
		x, y, _ := strings.Cut(data[payload:end], ",")
		w.column, _ = strconv.Atoi(x)
		w.row, _ = strconv.Atoi(y)
		data = data[:start] + data[end+1:]
	}
	// Bubble Tea v1 在完整帧末尾将光标重置到最后一行。只修正此处，不干预退出和其它控制序列。
	if isRenderCursorReset(data) {
		if w.column > 0 && w.row > 0 {
			data += ansi.CursorPosition(w.column, w.row) + ansi.ShowCursor
		} else {
			data += ansi.HideCursor
		}
	}
	n, err := io.WriteString(w.terminalFile, data)
	if err != nil {
		return 0, err
	}
	if n != len(data) {
		return 0, io.ErrShortWrite
	}
	return len(p), nil
}

func isRenderCursorReset(data string) bool {
	if !strings.HasSuffix(data, "H") {
		return false
	}
	start := strings.LastIndex(data, "\x1b[")
	if start < 0 {
		return false
	}
	row, column, found := strings.Cut(data[start+2:len(data)-1], ";")
	number, err := strconv.Atoi(row)
	return found && err == nil && number > 0 && (column == "" || column == "1")
}
