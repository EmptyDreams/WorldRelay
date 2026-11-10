package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// 只保存已经人工确认、服务端支持幂等的请求；账户 Token 从不落盘。
type operation struct {
	Path   string `json:"path"`
	Title  string `json:"title"`
	Target string `json:"target"`
	Body   object `json:"body"`
}
type journal struct{ dir string }

func (o *operation) validate() error {
	if o == nil || o.Body == nil || o.Body["confirm"] != true {
		return fmt.Errorf("缺少已确认的操作")
	}
	id := ""
	switch o.Path {
	case "worlds/restore":
		id = "restoreId"
	case "worlds/abandon":
		id = "abandonId"
	case "worlds/delete", "worlds/backup-delete":
		id = "deleteId"
	default:
		return fmt.Errorf("不支持重放此管理操作")
	}
	if _, ok := o.Body[id].(string); !ok {
		return fmt.Errorf("缺少原操作编号")
	}
	if _, ok := o.Body["worldId"].(string); !ok {
		return fmt.Errorf("缺少世界编号")
	}
	return nil
}

func (j journal) load() (*operation, error) {
	path := filepath.Join(j.dir, "pending.json")
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() > 16384 {
		return nil, fmt.Errorf("待确认操作必须是小于 16 KiB 的普通文件：%s", path)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	parsed, err := decodeObject(data)
	if err != nil {
		return nil, err
	}
	op := &operation{Path: text(parsed, "path"), Title: text(parsed, "title"), Target: text(parsed, "target"), Body: obj(parsed, "body")}
	return op, op.validate()
}

func (j journal) save(op *operation) error {
	if err := op.validate(); err != nil {
		return err
	}
	if err := os.MkdirAll(j.dir, 0700); err != nil {
		return err
	}
	data, err := json.Marshal(op)
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(j.dir, ".pending-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(data); err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(f.Name(), filepath.Join(j.dir, "pending.json")); err != nil {
		return err
	}
	return j.sync()
}

func (j journal) clear() error {
	if err := os.Remove(filepath.Join(j.dir, "pending.json")); err != nil && !os.IsNotExist(err) {
		return err
	}
	return j.sync()
}

func (j journal) sync() error {
	d, err := os.Open(j.dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
