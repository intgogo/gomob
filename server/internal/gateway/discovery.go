package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"time"
)

const (
	DiscoveryQueryType    = "gomob.discovery.v1"
	DiscoveryResponseType = "gomob.gateway.v1"
	DefaultDiscoveryAddr  = ":18809"
)

type DiscoveryResponse struct {
	Type     string `json:"type"`
	Service  string `json:"service"`
	Name     string `json:"name"`
	HTTPPort int    `json:"http_port"`
	ServerTS int64  `json:"server_ts"`
}

// StartDiscoveryResponder 在局域网 UDP 发现端口上回应 App 的网关发现请求。
//
// 协议故意极小：App 发 "gomob.discovery.v1"，gateway 回 gomob.gateway.v1 JSON。
// App 使用 UDP 包的来源 IP + 响应中的 http_port 组成唯一入口地址。
func StartDiscoveryResponder(
	ctx context.Context,
	discoveryAddr string,
	gatewayAddr string,
	name string,
	log *slog.Logger,
) error {
	if strings.TrimSpace(discoveryAddr) == "" {
		return nil
	}
	httpPort, err := gatewayHTTPPort(gatewayAddr)
	if err != nil {
		return err
	}
	udpAddr, err := net.ResolveUDPAddr("udp4", discoveryAddr)
	if err != nil {
		return err
	}
	conn, err := net.ListenUDP("udp4", udpAddr)
	if err != nil {
		return err
	}
	if strings.TrimSpace(name) == "" {
		name = "gomob-gateway"
	}
	if log != nil {
		log.Info("UDP 服务发现监听", "addr", discoveryAddr, "name", name, "http_port", httpPort)
	}
	go discoveryLoop(ctx, conn, httpPort, name, log)
	return nil
}

func discoveryLoop(ctx context.Context, conn *net.UDPConn, httpPort int, name string, log *slog.Logger) {
	defer func() { _ = conn.Close() }()
	buf := make([]byte, 512)
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		_ = conn.SetReadDeadline(time.Now().Add(time.Second))
		n, remote, err := conn.ReadFromUDP(buf)
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) && netErr.Timeout() {
				continue
			}
			if errors.Is(err, net.ErrClosed) {
				return
			}
			if log != nil {
				log.Warn("UDP 服务发现读取失败", "err", err)
			}
			continue
		}
		if !isDiscoveryQuery(buf[:n]) {
			continue
		}
		resp, err := json.Marshal(DiscoveryResponse{
			Type:     DiscoveryResponseType,
			Service:  "gomob-gateway",
			Name:     name,
			HTTPPort: httpPort,
			ServerTS: time.Now().UnixMilli(),
		})
		if err != nil {
			continue
		}
		if _, err := conn.WriteToUDP(resp, remote); err != nil && log != nil {
			log.Warn("UDP 服务发现响应失败", "remote", remote.String(), "err", err)
		}
	}
}

func isDiscoveryQuery(payload []byte) bool {
	msg := strings.TrimSpace(string(payload))
	if msg == DiscoveryQueryType {
		return true
	}
	var env struct {
		Type string `json:"type"`
	}
	if err := json.Unmarshal(payload, &env); err != nil {
		return false
	}
	return env.Type == DiscoveryQueryType
}

func gatewayHTTPPort(addr string) (int, error) {
	host, portText, err := net.SplitHostPort(addr)
	if err != nil {
		if strings.HasPrefix(addr, ":") {
			portText = strings.TrimPrefix(addr, ":")
		} else if !strings.Contains(addr, ":") {
			portText = addr
		} else {
			return 0, err
		}
	} else {
		_ = host
	}
	port, err := strconv.Atoi(portText)
	if err != nil {
		return 0, err
	}
	if port <= 0 || port > 65535 {
		return 0, errors.New("gateway http port out of range")
	}
	return port, nil
}
