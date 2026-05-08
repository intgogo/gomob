package gateway

import (
	"context"
	"encoding/json"
	"net"
	"testing"
	"time"
)

func TestDiscoveryQuery(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want bool
	}{
		{name: "plain", in: DiscoveryQueryType, want: true},
		{name: "json", in: `{"type":"gomob.discovery.v1"}`, want: true},
		{name: "other", in: `{"type":"other"}`, want: false},
		{name: "garbage", in: `hello`, want: false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isDiscoveryQuery([]byte(tc.in)); got != tc.want {
				t.Fatalf("isDiscoveryQuery()=%v want %v", got, tc.want)
			}
		})
	}
}

func TestGatewayHTTPPort(t *testing.T) {
	cases := []struct {
		addr string
		want int
	}{
		{":18808", 18808},
		{"0.0.0.0:18808", 18808},
		{"127.0.0.1:8808", 8808},
		{"18808", 18808},
	}
	for _, tc := range cases {
		t.Run(tc.addr, func(t *testing.T) {
			got, err := gatewayHTTPPort(tc.addr)
			if err != nil {
				t.Fatalf("gatewayHTTPPort() err=%v", err)
			}
			if got != tc.want {
				t.Fatalf("gatewayHTTPPort()=%d want %d", got, tc.want)
			}
		})
	}
}

func TestDiscoveryResponder(t *testing.T) {
	tmp, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatalf("listen temp udp: %v", err)
	}
	addr := tmp.LocalAddr().String()
	_ = tmp.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := StartDiscoveryResponder(ctx, addr, "127.0.0.1:18808", "test-gateway", nil); err != nil {
		t.Fatalf("StartDiscoveryResponder() err=%v", err)
	}

	client, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		t.Fatalf("listen client udp: %v", err)
	}
	defer func() { _ = client.Close() }()
	serverAddr, err := net.ResolveUDPAddr("udp4", addr)
	if err != nil {
		t.Fatalf("resolve server addr: %v", err)
	}
	if _, err := client.WriteToUDP([]byte(DiscoveryQueryType), serverAddr); err != nil {
		t.Fatalf("write query: %v", err)
	}
	_ = client.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 512)
	n, _, err := client.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	var resp DiscoveryResponse
	if err := json.Unmarshal(buf[:n], &resp); err != nil {
		t.Fatalf("unmarshal response: %v", err)
	}
	if resp.Type != DiscoveryResponseType {
		t.Fatalf("type=%q want %q", resp.Type, DiscoveryResponseType)
	}
	if resp.Name != "test-gateway" {
		t.Fatalf("name=%q want test-gateway", resp.Name)
	}
	if resp.HTTPPort != 18808 {
		t.Fatalf("http_port=%d want 18808", resp.HTTPPort)
	}
}
