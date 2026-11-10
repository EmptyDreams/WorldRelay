//go:build !linux

package main

import "fmt"

func (j journal) lock() (func(), error) {
	return nil, fmt.Errorf("交互管理终端目前支持 Linux；--check 可用于只读接口检查")
}
