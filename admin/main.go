// WorldRelay 的本机管理终端；仅通过 HTTP 操作服务，不直接修改世界文件。
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"time"

	tea "github.com/charmbracelet/bubbletea"
)

var version = "dev"

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "WorldRelay:", clean(err.Error()))
		os.Exit(1)
	}
}

func run() error {
	timeout := flag.Duration("timeout", 10*time.Second, "单次 HTTP 请求超时")
	refresh := flag.Duration("refresh", 5*time.Second, "概览和用户列表刷新间隔（0 关闭；世界列表手动刷新）")
	stateDir := flag.String("state-dir", "", "待确认操作目录（默认可执行文件旁的 state/）")
	check := flag.Bool("check", false, "只读检查管理接口并输出概览 JSON，不启动终端界面")
	showVersion := flag.Bool("version", false, "显示管理工具版本")
	flag.Parse()
	if *showVersion {
		fmt.Println("worldrelay-admin", version)
		return nil
	}
	if flag.NArg() != 0 || *timeout <= 0 || *refresh < 0 {
		return fmt.Errorf("请检查超时和刷新间隔；使用 --help 查看参数")
	}
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	deployment, port, err := loadDeployment(executable)
	if err != nil {
		return err
	}
	api := newClient(port, *timeout)
	defer api.http.CloseIdleConnections()
	if *check {
		response, err := api.call(context.Background(), "status", nil)
		if err != nil {
			return err
		}
		if _, ok := response.body["version"].(string); !ok {
			return fmt.Errorf("管理接口未返回服务概览，请检查端口或更新服务端")
		}
		return json.NewEncoder(os.Stdout).Encode(response.body)
	}
	if *stateDir == "" {
		*stateDir = filepath.Join(deployment, "state")
	}
	store := journal{dir: *stateDir}
	unlock, err := store.lock()
	if err != nil {
		return err
	}
	defer unlock()
	pending, err := store.load()
	if err != nil {
		return fmt.Errorf("读取待确认操作失败：%w", err)
	}
	model := newModel(api, store, *refresh)
	model.pending = pending
	if pending != nil {
		model.screen = operationScreen
	}
	_, err = tea.NewProgram(model, tea.WithOutput(&cursorOutput{terminalFile: os.Stdout}), tea.WithAltScreen(), tea.WithMouseCellMotion()).Run()
	return err
}
