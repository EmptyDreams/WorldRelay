//go:build linux

package main

import "testing"

// 验证两个终端不能覆盖同一待确认操作记录，退出后可重新取得操作目录。
func TestExclusiveConsoleSession(t *testing.T) {
	j := journal{t.TempDir()}
	unlock, err := j.lock()
	if err != nil {
		t.Fatal(err)
	}
	if second, err := j.lock(); err == nil {
		second()
		unlock()
		t.Fatal("允许同时使用同一操作记录")
	}
	unlock()
	next, err := j.lock()
	if err != nil {
		t.Fatal(err)
	}
	next()
}
