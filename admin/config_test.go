package main

import (
	"os"
	"path/filepath"
	"testing"
)

// 验证管理端口遵循服务端 properties 格式，包括默认值、重复键、续行和转义。
func TestManagementPortUsesServerProperties(t *testing.T) {
	for _, test := range []struct {
		name, config string
		port         int
	}{
		{"default", "# 中文说明\nhttp.port=2000\n", 8081},
		{"configured", "registration.port=2001\n", 2001},
		{"colon", " \tregistration.port \t: 2201 \t\r\n", 2201},
		{"space", "registration.port  3201\r", 3201},
		{"comments", "  # registration.port=1\n! registration.port=2\nregistration.port=4201", 4201},
		{"duplicate", "registration.port=2201\nregistration.port=5201", 5201},
		{"continued", "registration.port=62\\\r\n  01\n", 6201},
		{"continued-key", "registration.\\\n  port=7201", 7201},
		{"escaped", "registration\\.port=\\u0038\\u0032\\u0030\\u0031", 8201},
		{"unrelated-continuation", "ssh.public-host=host\\\nregistration.port=9\n", 8081},
		{"end-continuation", "registration.port=9201\\", 9201},
	} {
		t.Run(test.name, func(t *testing.T) {
			port, err := managementPort(test.config)
			if err != nil || port != test.port {
				t.Fatalf("port=%d error=%v, expected %d", port, err, test.port)
			}
		})
	}
}

// 验证不明确或无法连接的端口会报错，避免静默连到默认端口或玩家接口。
func TestManagementPortRejectsInvalidConfiguration(t *testing.T) {
	for _, value := range []string{"", " ", "0", "-1", "65536", "not-a-port", "2001 # comment", `\u00ZZ`, `\u20`} {
		if port, err := managementPort("registration.port=" + value); err == nil {
			t.Fatalf("accepted %q as %d", value, port)
		}
	}
}

// 验证原生版和 JAR 版均读取可执行文件旁的配置，并能通过其他目录的符号链接启动。
func TestDeploymentFollowsExecutableInsteadOfWorkingDirectory(t *testing.T) {
	root := t.TempDir()
	caller := filepath.Join(root, "caller")
	if err := os.Mkdir(caller, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(caller, "server.properties"), []byte("registration.port=1\n"), 0600); err != nil {
		t.Fatal(err)
	}
	t.Chdir(caller)
	for _, server := range []string{"worldrelay-server", "worldrelay-server.jar"} {
		t.Run(server, func(t *testing.T) {
			directory := t.TempDir()
			executable := filepath.Join(directory, "worldrelay-admin")
			for name, contents := range map[string]string{
				"worldrelay-admin": "fixture", server: "fixture", "server.properties": "registration.port=2001\n",
			} {
				if err := os.WriteFile(filepath.Join(directory, name), []byte(contents), 0600); err != nil {
					t.Fatal(err)
				}
			}
			resolved, port, err := loadDeployment(executable)
			if err != nil || resolved != directory || port != 2001 {
				t.Fatalf("deployment=%q port=%d error=%v", resolved, port, err)
			}
			link := filepath.Join(caller, server+"-admin")
			if err := os.Symlink(executable, link); err != nil {
				t.Fatal(err)
			}
			resolved, port, err = loadDeployment(link)
			if err != nil || resolved != directory || port != 2001 {
				t.Fatalf("symlink deployment=%q port=%d error=%v", resolved, port, err)
			}
		})
	}
}

// 验证服务器程序和配置必须与管理终端同目录，目录不能冒充服务器程序。
func TestDeploymentRequiresServerAndConfiguration(t *testing.T) {
	for _, missing := range []string{"server", "config", "server-is-directory"} {
		t.Run(missing, func(t *testing.T) {
			directory := t.TempDir()
			executable := filepath.Join(directory, "worldrelay-admin")
			if err := os.WriteFile(executable, nil, 0600); err != nil {
				t.Fatal(err)
			}
			if missing == "server-is-directory" {
				if err := os.Mkdir(filepath.Join(directory, "worldrelay-server"), 0700); err != nil {
					t.Fatal(err)
				}
			} else if missing != "server" {
				if err := os.WriteFile(filepath.Join(directory, "worldrelay-server"), nil, 0600); err != nil {
					t.Fatal(err)
				}
			}
			if missing != "config" {
				if err := os.WriteFile(filepath.Join(directory, "server.properties"), []byte("registration.port=2001"), 0600); err != nil {
					t.Fatal(err)
				}
			}
			if _, _, err := loadDeployment(executable); err == nil {
				t.Fatal("accepted incomplete deployment")
			}
		})
	}
}
