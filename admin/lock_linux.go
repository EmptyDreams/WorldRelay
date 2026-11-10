//go:build linux

package main

import (
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/sys/unix"
)

// 进程退出后内核自动释放；保留锁文件，避免删除后两个进程锁住不同 inode。
func (j journal) lock() (func(), error) {
	if err := os.MkdirAll(j.dir, 0700); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(filepath.Join(j.dir, "console.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err := unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
		f.Close()
		return nil, fmt.Errorf("同一操作目录已有管理终端运行，或无法加锁：%w", err)
	}
	return func() { _ = unix.Flock(int(f.Fd()), unix.LOCK_UN); _ = f.Close() }, nil
}
