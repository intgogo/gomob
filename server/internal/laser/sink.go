package laser

import (
	"context"
	"log/slog"
	"time"
)

// sink.go = 实时出口与设备门控的具体实现：
//   - natsSink：把采集中的增量点帧 / 状态发 NATS，signaling.LaserBridge 按 owner 路由到 ws；
//   - devctlGate：live 下两单元的 SCAN_START / SCAN_STOP；
//   - deviceProber：devctl.Probe 的默认实现。

// NATS 主题（signaling 侧 LaserBridge 订阅这两个；与 scan.fusion_done 同范式，原样转发按 owner 路由）。
const (
	TopicLaserPoints = "laser.points" // 采集中增量点（unit 0/1）
	TopicLaserStatus = "laser.status" // 状态机变更
)

// 单条 laser.points 最多携带的点数；超出则切分，避免触顶 NATS 1MB 消息上限。
// 8192 点 ≈ 96KB（含 JSON 膨胀仍 < 1MB），远超单条 PTS 扫描线点数，正常不触发切分。
const maxPointsPerMsg = 8192

// LaserPointsMsg = laser.points 载荷。owner_user_id 供 LaserBridge 路由（不外发给端，仅路由）。
type LaserPointsMsg struct {
	OwnerUserID *int64    `json:"owner_user_id,omitempty"`
	SessionKey  string    `json:"session_key"`
	Unit        int       `json:"unit"` // 0=unitA, 1=unitB（融合云不走实时，经 scan.fusion_done 的 PCD 下载）
	Points      []float32 `json:"points"`
	HAngleDeg   float32   `json:"h_angle_deg"`
}

// LaserStatusMsg = laser.status 载荷。
type LaserStatusMsg struct {
	OwnerUserID *int64 `json:"owner_user_id,omitempty"`
	SessionKey  string `json:"session_key"`
	State       string `json:"state"`
	FramesA     int    `json:"frames_a"`
	FramesB     int    `json:"frames_b"`
}

// natsSink 实现 Sink。
type natsSink struct {
	pub        Publisher
	sessionKey string
	owner      *int64
	log        *slog.Logger
}

// NewNATSSink 建实时出口。pub 为空则退化为 nop（不阻断扫描）。
func NewNATSSink(pub Publisher, sessionKey string, owner *int64, log *slog.Logger) Sink {
	if pub == nil {
		return nopSink{}
	}
	if log == nil {
		log = slog.Default()
	}
	return &natsSink{pub: pub, sessionKey: sessionKey, owner: owner, log: log}
}

func (s *natsSink) Points(f PointFrame) {
	// 融合整云(unit=2)体量巨大（百万点级，远超 NATS 单消息上限），不走实时；端侧经
	// scan.fusion_done 的 PCD object key presign 下载。实时仅推两镜头增量(unit 0/1)。
	if f.Unit != 0 && f.Unit != 1 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	// 按 maxPointsPerMsg 切分（正常单帧远小于此，不触发）。
	pts := f.XYZmm
	for off := 0; off < len(pts); off += maxPointsPerMsg * 3 {
		end := off + maxPointsPerMsg*3
		if end > len(pts) {
			end = len(pts)
		}
		msg := LaserPointsMsg{
			OwnerUserID: s.owner,
			SessionKey:  s.sessionKey,
			Unit:        f.Unit,
			Points:      pts[off:end],
			HAngleDeg:   f.HAngleDeg,
		}
		if err := s.pub.Publish(ctx, TopicLaserPoints, msg); err != nil {
			s.log.Warn("发布 laser.points 失败", "err", err, "session", s.sessionKey)
			return
		}
	}
}

func (s *natsSink) Status(state string, a, b int) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	msg := LaserStatusMsg{OwnerUserID: s.owner, SessionKey: s.sessionKey, State: state, FramesA: a, FramesB: b}
	if err := s.pub.Publish(ctx, TopicLaserStatus, msg); err != nil {
		s.log.Warn("发布 laser.status 失败", "err", err, "session", s.sessionKey)
	}
}

// devctlGate 实现 DeviceGate：对两单元发 SCAN_START / SCAN_STOP。
type devctlGate struct {
	a, b *DeviceClient
	log  *slog.Logger
}

// NewDevctlGate 建门控。
func NewDevctlGate(ipA, ipB string, log *slog.Logger) DeviceGate {
	if log == nil {
		log = slog.Default()
	}
	return &devctlGate{a: NewDeviceClient(ipA, 0), b: NewDeviceClient(ipB, 0), log: log}
}

func (g *devctlGate) Start(ctx context.Context) error {
	if err := g.a.ControlScan(ctx, ScanStart); err != nil {
		return err
	}
	if err := g.b.ControlScan(ctx, ScanStart); err != nil {
		// A 已起，B 失败：尽力把 A 停回，避免单边空转。
		_ = g.a.ControlScan(context.WithoutCancel(ctx), ScanStop)
		return err
	}
	return nil
}

func (g *devctlGate) Stop(ctx context.Context) error {
	// 两个都尽力发，合并错误以日志为准（停机不应因单边失败而漏发另一边）。
	ea := g.a.ControlScan(ctx, ScanStop)
	eb := g.b.ControlScan(ctx, ScanStop)
	if ea != nil {
		g.log.Warn("unitA SCAN_STOP 失败", "err", ea)
	}
	if eb != nil {
		g.log.Warn("unitB SCAN_STOP 失败", "err", eb)
	}
	if ea != nil {
		return ea
	}
	return eb
}

// Prober = 探活一个单元（可注入 fake 做 httptest）。
type Prober interface {
	Probe(ctx context.Context, ip string) ProbeResult
}

// deviceProber 默认实现：每次新建短超时 DeviceClient 探活。
type deviceProber struct{ timeout time.Duration }

// NewDeviceProber 建默认 prober。
func NewDeviceProber(timeout time.Duration) Prober { return &deviceProber{timeout: timeout} }

func (p *deviceProber) Probe(ctx context.Context, ip string) ProbeResult {
	return NewDeviceClient(ip, p.timeout).Probe(ctx)
}
