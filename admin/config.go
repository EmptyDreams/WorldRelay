package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// 配置和默认状态目录始终跟随实际可执行文件，不受启动目录或入口符号链接影响。
func loadDeployment(executable string) (string, int, error) {
	resolved, err := filepath.EvalSymlinks(executable)
	if err != nil {
		return "", 0, fmt.Errorf("无法定位管理终端：%w", err)
	}
	directory := filepath.Dir(resolved)
	serverPresent := false
	for _, name := range []string{"worldrelay-server", "worldrelay-server.jar"} {
		info, err := os.Stat(filepath.Join(directory, name))
		if err == nil && info.Mode().IsRegular() {
			serverPresent = true
			break
		}
		if err != nil && !os.IsNotExist(err) {
			return "", 0, fmt.Errorf("无法检查同目录的服务器程序：%w", err)
		}
	}
	if !serverPresent {
		return "", 0, fmt.Errorf("请将 worldrelay-admin 与 worldrelay-server 或 worldrelay-server.jar 放在同一目录")
	}
	path := filepath.Join(directory, "server.properties")
	data, err := os.ReadFile(path)
	if err != nil {
		return "", 0, fmt.Errorf("无法读取同目录的 server.properties，请先运行服务器的 --config 初始化配置：%w", err)
	}
	port, err := managementPort(string(data))
	if err != nil {
		return "", 0, fmt.Errorf("server.properties：%w", err)
	}
	return directory, port, nil
}

// 与服务端 Properties.load(Reader) 一样处理注释、分隔符、续行、转义及重复键。
// 只读取管理端口，其余配置由服务端负责校验。
func managementPort(data string) (int, error) {
	value := "8081"
	data = strings.ReplaceAll(strings.ReplaceAll(data, "\r\n", "\n"), "\r", "\n")
	logical := ""
	continued := false
	for _, line := range strings.Split(data+"\n", "\n") {
		line = strings.TrimLeft(line, " \t\f")
		if !continued && (line == "" || line[0] == '#' || line[0] == '!') {
			continue
		}
		logical += line
		backslashes := len(logical) - len(strings.TrimRight(logical, "\\"))
		continued = backslashes%2 == 1
		if continued {
			logical = logical[:len(logical)-1]
			continue
		}
		keyEnd := 0
		escaped := false
		for keyEnd < len(logical) {
			character := logical[keyEnd]
			if !escaped && strings.ContainsRune("=: \t\f", rune(character)) {
				break
			}
			escaped = !escaped && character == '\\'
			keyEnd++
		}
		key, err := unescapeProperty(logical[:keyEnd])
		if err != nil {
			return 0, err
		}
		if key == "registration.port" {
			rest := strings.TrimLeft(logical[keyEnd:], " \t\f")
			if len(rest) > 0 && (rest[0] == '=' || rest[0] == ':') {
				rest = rest[1:]
			}
			value, err = unescapeProperty(strings.TrimLeft(rest, " \t\f"))
			if err != nil {
				return 0, err
			}
		}
		logical = ""
	}
	port, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil || port < 1 || port > 65535 {
		return 0, fmt.Errorf("registration.port 必须为 1 到 65535 之间的固定端口")
	}
	return port, nil
}

func unescapeProperty(value string) (string, error) {
	var decoded strings.Builder
	for index := 0; index < len(value); index++ {
		if value[index] != '\\' {
			decoded.WriteByte(value[index])
			continue
		}
		index++
		if index == len(value) {
			break
		}
		switch value[index] {
		case 't':
			decoded.WriteByte('\t')
		case 'r':
			decoded.WriteByte('\r')
		case 'n':
			decoded.WriteByte('\n')
		case 'f':
			decoded.WriteByte('\f')
		case 'u':
			if index+4 >= len(value) {
				return "", fmt.Errorf("配置包含不完整的 Unicode 转义")
			}
			character, err := strconv.ParseUint(value[index+1:index+5], 16, 16)
			if err != nil {
				return "", fmt.Errorf("配置包含无效的 Unicode 转义")
			}
			decoded.WriteRune(rune(character))
			index += 4
		default:
			decoded.WriteByte(value[index])
		}
	}
	return decoded.String(), nil
}
