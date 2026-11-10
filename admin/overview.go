package main

import (
	"fmt"
	"strings"

	"github.com/charmbracelet/lipgloss"
)

type overviewCard struct {
	title   string
	lines   []string
	stretch bool
}

func overviewWidths(width, count int) []int {
	widths := make([]int, count)
	for i := range widths {
		widths[i] = (width - count + 1) / count
		if i < (width-count+1)%count {
			widths[i]++
		}
	}
	return widths
}

func overviewRow(cards []overviewCard, widths []int) string {
	height := 0
	for i, item := range cards {
		height = max(height, wrappedRows(item.lines, widths[i]-4))
	}
	var cells []string
	for i, item := range cards {
		if i > 0 {
			cells = append(cells, " ")
		}
		cells = append(cells, flexCard(item.title, widths[i], item.lines, height, item.stretch))
	}
	return lipgloss.JoinHorizontal(lipgloss.Top, cells...)
}

func overviewGrid(cards []overviewCard, width int) string {
	columns := 2
	if width < 68 {
		columns = 1
	} else if width >= 110 {
		columns = 3
	}
	var rows []string
	for start := 0; start < len(cards); start += columns {
		end := min(len(cards), start+columns)
		rows = append(rows, overviewRow(cards[start:end], overviewWidths(width, end-start)))
	}
	return strings.Join(rows, "\n")
}

var processStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("42"))
var systemStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("214"))
var freeStyle = lipgloss.NewStyle().Foreground(lipgloss.Color("240"))

// 系统总占用已包含本进程；三段相加恰好占满整条，不能把进程占用再加一次。
func resourceBar(total, system, process int64, width int) string {
	if total <= 0 || system < 0 || process < 0 {
		return muted.Render("暂无使用率数据")
	}
	system = min(system, total)
	process = min(process, system)
	occupied := int(float64(system)/float64(total)*float64(width) + 0.5)
	owned := int(float64(process)/float64(total)*float64(width) + 0.5)
	return processStyle.Render(strings.Repeat("█", owned)) + systemStyle.Render(strings.Repeat("▓", occupied-owned)) + freeStyle.Render(strings.Repeat("░", width-occupied))
}

func knownMetric(data object, key string) int64 {
	if data[key] == nil {
		return -1
	}
	return integer(data, key)
}

func sizeValue(data object, key string) string {
	if data[key] == nil {
		return "—"
	}
	return byteSize(integer(data, key))
}

func sizeRange(data object, used, total string) string {
	if data[used] == nil && data[total] == nil {
		return "—"
	}
	return sizeValue(data, used) + " / " + sizeValue(data, total)
}

func usageValue(data object, key string) string {
	if data[key] == nil {
		return "—"
	}
	return fmt.Sprintf("%.1f%%", float64(integer(data, key))/10)
}

func loadValue(data object) string {
	if data["loadAverageMilli"] == nil {
		return "—"
	}
	return fmt.Sprintf("%.2f", float64(integer(data, "loadAverageMilli"))/1000)
}

func rateValue(data object, key string) string {
	if data[key] == nil {
		return "—"
	}
	return byteSize(integer(data, key)) + "/s"
}

func (m model) overview() string {
	width := m.bodyWidth()
	s := m.status
	prefix := ""
	if m.notice != "" {
		messages := []string{warningStyle.Render(clean(m.notice))}
		if s != nil {
			messages = append(messages, muted.Render("以下统计为最近一次成功获取的数据。"))
		}
		prefix = card("服务状态异常", width, messages, 0) + "\n"
	}
	if s == nil {
		message := "正在读取服务概览…"
		if m.notice != "" {
			message = "检查管理端口后重新启动管理终端。"
			if m.refresh > 0 {
				message = "检查管理端口后可等待自动重试，或重新启动管理终端。"
			}
		}
		return prefix + card("服务概览", width, []string{message}, 0)
	}
	heap, tunnel, tasks := obj(s, "heap"), obj(s, "tunnel"), obj(s, "tasks")
	system := obj(s, "system")
	cpu, memory, network := obj(system, "cpu"), obj(system, "memory"), obj(system, "network")
	runtime := "Java " + text(s, "javaVersion") + " / JVM"
	if flagValue(s, "nativeImage") {
		runtime = "Java " + text(s, "javaVersion") + " / Native"
	}
	run := []string{metric("服务版本", text(s, "version")), metric("运行环境", runtime), metric("运行时间", runtimeText(integer(s, "uptimeMillis"))), metric("启动时间", localTime(text(s, "startedAt")))}
	connections := []string{metric("世界", "总计 "+text(s, "worlds")+" · 有房主 "+text(s, "ownedWorlds")), metric("用户", "总计 "+text(s, "users")+" · 在线 "+text(s, "onlineUsers")), metric("隧道模式", strings.ToUpper(text(tunnel, "mode"))), metric("隧道连接", text(tunnel, "connections"))}
	jobs := []string{metric("排队 / 执行", text(tasks, "queued")+" / "+text(tasks, "running")), metric("结果缓存", text(tasks, "cached")), metric("完成 / 失败", text(s, "completedOperations")+" / "+text(s, "failedOperations")), metric("已取消", text(s, "cancelledOperations"))}
	wide := width >= 110
	widths := overviewWidths(width, 3)
	span := widths[0] + 1 + widths[1]
	var disk []string
	for _, item := range []struct{ key, label string }{{"worlds", "存档可用"}, {"transfers", "传输可用"}} {
		store := obj(obj(s, "storage"), item.key)
		line := metric(item.label, sizeRange(store, "usableBytes", "totalBytes"))
		if wide {
			bar := muted.Render("—")
			if store["usableBytes"] != nil && store["totalBytes"] != nil {
				bar = muted.Render("已用 ") + progress(integer(store, "totalBytes")-integer(store, "usableBytes"), integer(store, "totalBytes"), widths[2]-9)
			}
			line += "\n" + bar
		}
		disk = append(disk, line)
	}
	processor := []string{metric("系统使用率", usageValue(cpu, "systemUsagePermille")), metric("进程使用率", usageValue(cpu, "processUsagePermille")), metric("核心总/可用", text(cpu, "logicalProcessors")+" / "+text(cpu, "availableProcessors")), metric("1分钟负载", loadValue(cpu))}
	systemUsed := "—"
	if memory["totalBytes"] != nil && memory["availableBytes"] != nil {
		systemUsed = byteSize(max(0, integer(memory, "totalBytes")-integer(memory, "availableBytes"))) + " / " + sizeValue(memory, "totalBytes")
	}
	ram := []string{metric("系统已用", systemUsed), metric("进程常驻", sizeValue(memory, "processRssBytes")), metric("堆已使用", sizeRange(heap, "usedBytes", "maxBytes")), metric("堆已分配", sizeValue(heap, "committedBytes"))}
	if flagValue(s, "nativeImage") {
		ram[2] = metric("堆已使用", sizeValue(heap, "usedBytes"))
		ram[3] = metric("堆上限", sizeValue(heap, "maxBytes"))
	}
	traffic := []string{metric("接收速率", rateValue(network, "receiveBytesPerSecond")), metric("发送速率", rateValue(network, "sendBytesPerSecond")), metric("累计接收", sizeValue(network, "receivedBytes")), metric("累计发送", sizeValue(network, "sentBytes"))}
	if wide {
		processor = append(processor, resourceBar(1000, knownMetric(cpu, "systemUsagePermille"), knownMetric(cpu, "processUsagePermille"), span-4))
		used := int64(-1)
		if memory["totalBytes"] != nil && memory["availableBytes"] != nil {
			used = max(0, integer(memory, "totalBytes")-integer(memory, "availableBytes"))
		}
		ram = append(ram, resourceBar(knownMetric(memory, "totalBytes"), used, knownMetric(memory, "processRssBytes"), span-4))
		return prefix + strings.Join([]string{
			overviewRow([]overviewCard{{"运行信息", run, false}, {"联机情况", connections, false}, {"后台任务", jobs, true}}, widths),
			overviewRow([]overviewCard{{"CPU", processor, false}, {"磁盘 · 可用/总量", disk, false}}, []int{span, widths[2]}),
			overviewRow([]overviewCard{{"内存", ram, false}, {"网络 · 非回环网卡", traffic, false}}, []int{span, widths[2]}),
		}, "\n")
	}
	return prefix + overviewGrid([]overviewCard{{"运行信息", run, false}, {"联机情况", connections, false}, {"后台任务", jobs, true}, {"磁盘 · 可用/总量", disk, false}, {"CPU", processor, false}, {"内存", ram, false}, {"网络 · 非回环网卡", traffic, false}}, width)
}
