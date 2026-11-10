package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"time"
)

type object = map[string]any

type client struct {
	base string
	http *http.Client
}
type response struct {
	status int
	body   object
}
type apiError struct {
	status                   int
	code, message, requestID string
}

func (e *apiError) Error() string {
	if e.status == 404 {
		return "接口或记录不存在（404），请检查服务端版本和所选记录；请求号 " + clean(e.requestID)
	}
	return fmt.Sprintf("HTTP %d · %s · %s（请求号 %s）", e.status, clean(e.code), clean(e.message), clean(e.requestID))
}

func newClient(port int, timeout time.Duration) *client {
	return &client{base: "http://127.0.0.1:" + strconv.Itoa(port) + "/api/v1/", http: &http.Client{
		Timeout: timeout,
		// 管理请求始终直连 IPv4 回环，不受环境代理或重定向影响。
		Transport:     &http.Transport{DialContext: (&net.Dialer{Timeout: timeout}).DialContext, MaxIdleConns: 4, IdleConnTimeout: 30 * time.Second},
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
	}}
}

func (c *client) call(ctx context.Context, path string, body object) (response, error) {
	method := http.MethodGet
	var input io.Reader
	if body != nil {
		method = http.MethodPost
		data, err := json.Marshal(body)
		if err != nil {
			return response{}, err
		}
		if len(data) > 4096 {
			return response{}, fmt.Errorf("请求超过接口的 4096 字节限制，请缩短输入")
		}
		input = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+path, input)
	if err != nil {
		return response{}, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
	}
	req.Header.Set("Accept", "application/json")
	r, err := c.http.Do(req)
	if err != nil {
		return response{}, fmt.Errorf("无法连接本机管理接口：%w", err)
	}
	defer r.Body.Close()
	data, err := io.ReadAll(io.LimitReader(r.Body, 8*1024*1024+1))
	if err != nil {
		return response{}, fmt.Errorf("读取管理接口响应失败：%w", err)
	}
	if len(data) > 8*1024*1024 {
		return response{}, fmt.Errorf("管理接口响应超过 8 MiB")
	}
	parsed, err := decodeObject(data)
	if r.StatusCode < 200 || r.StatusCode > 299 {
		return response{}, &apiError{r.StatusCode, text(parsed, "code"), text(parsed, "message"), r.Header.Get("X-Request-Id")}
	}
	if err != nil {
		return response{}, fmt.Errorf("管理接口未返回有效 JSON，请检查端口：%w", err)
	}
	return response{r.StatusCode, parsed}, nil
}

func decodeObject(data []byte) (object, error) {
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber() // epoch 和版本必须原样往返，不能经过 float64。
	var result object
	if err := d.Decode(&result); err != nil {
		return nil, err
	}
	if result == nil {
		return nil, fmt.Errorf("响应不是 JSON 对象")
	}
	var trailing any
	if err := d.Decode(&trailing); err != io.EOF {
		return nil, fmt.Errorf("JSON 对象后存在额外内容")
	}
	return result, nil
}

func text(o object, key string) string {
	if o == nil || o[key] == nil {
		return "—"
	}
	switch v := o[key].(type) {
	case string:
		return v
	case json.Number:
		return v.String()
	case bool:
		if v {
			return "是"
		}
		return "否"
	default:
		return "—"
	}
}
func obj(o object, key string) object { v, _ := o[key].(map[string]any); return v }
func rows(o object, key string) []object {
	var result []object
	values, _ := o[key].([]any)
	for _, value := range values {
		if v, ok := value.(map[string]any); ok {
			result = append(result, v)
		}
	}
	return result
}
func integer(o object, key string) int64  { n, _ := o[key].(json.Number); v, _ := n.Int64(); return v }
func flagValue(o object, key string) bool { b, _ := o[key].(bool); return b }
