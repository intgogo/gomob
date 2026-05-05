// Package shaperefclient —— cv-engine 调用 shape-ref 服务的 HTTP 客户端（M-S9.x cv-engine 接入）。
//
// shape-ref 提供 GET /v1/catalog/vehicles/{vmid}/shape 返 active 版本元数据 + 签名 mesh URL。
// cv-engine 在做 shape_compare 时按 vehicle_model_id 拉这条 active 记录，用其
// triangle_count / point_count / bbox / coverage / qc_score 字段与端侧扫描的元数据
// 做"元数据级"质量比对（无需下载完整 mesh）。
//
// 真"几何级"比对（chamfer / Hausdorff）将在 mesh 解析后置阶段加入；本客户端的契约
// 不变，按需补 FetchMesh 拉签名 URL 拿 .ply / .glb 字节。
package shaperefclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// BBox 3D 包围盒。各字段缺省 (nil) 表示该维度未提供。
type BBox struct {
	MinX *float32 `json:"min_x,omitempty"`
	MinY *float32 `json:"min_y,omitempty"`
	MinZ *float32 `json:"min_z,omitempty"`
	MaxX *float32 `json:"max_x,omitempty"`
	MaxY *float32 `json:"max_y,omitempty"`
	MaxZ *float32 `json:"max_z,omitempty"`
}

// ActiveShape shape-ref 返的 active 版本子集。
//
// 与 server/internal/shaperef/handler.go 的 shapeDTO 字段对齐，仅取 cv-engine 关心的部分。
type ActiveShape struct {
	ID             string   `json:"id"`
	VehicleModelID string   `json:"vehicle_model_id"`
	VersionLabel   string   `json:"version_label"`
	Status         string   `json:"status"`
	Format         string   `json:"format"`
	MeshObjectKey  string   `json:"mesh_object_key"`
	MeshSHA256     string   `json:"mesh_sha256"`
	MeshSizeBytes  int64    `json:"mesh_size_bytes"`
	TriangleCount  *int64   `json:"triangle_count,omitempty"`
	PointCount     *int64   `json:"point_count,omitempty"`
	BBox           *BBox    `json:"bbox,omitempty"`
	Coverage       *float32 `json:"coverage,omitempty"`
	QCScore        *float32 `json:"qc_score,omitempty"`
	MeshURL        string   `json:"mesh_url,omitempty"`         // 5min 签名 URL
	MeshURLExpire  string   `json:"mesh_url_expire_at,omitempty"`
}

// Client cv-engine 侧 shape-ref HTTP 客户端。
type Client struct {
	BaseURL string
	Timeout time.Duration
	HTTP    *http.Client
}

// NewClient 默认 5s 超时。
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = "http://127.0.0.1:18056"
	}
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		Timeout: 5 * time.Second,
		HTTP:    &http.Client{Timeout: 5 * time.Second},
	}
}

type activeResp struct {
	Code int         `json:"code"`
	Data ActiveShape `json:"data"`
	Msg  string      `json:"message,omitempty"`
}

// ErrNotFound shape-ref 该 vehicle_model_id 当前没有 active shape。
var ErrNotFound = errors.New("shaperef: no active shape")

// GetActive 拉指定车型当前 active 的 shape 记录（含签名 mesh URL）。
func (c *Client) GetActive(ctx context.Context, vehicleModelID int64) (*ActiveShape, error) {
	if vehicleModelID <= 0 {
		return nil, errors.New("vehicle_model_id 必须 > 0")
	}
	u := fmt.Sprintf("%s/v1/catalog/vehicles/%d/shape", c.BaseURL, vehicleModelID)
	rctx, cancel := context.WithTimeout(ctx, c.Timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("shaperef %s: %w", u, err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))

	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrNotFound
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("shaperef http=%d body=%s", resp.StatusCode, string(body))
	}
	var out activeResp
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("shaperef 响应解析: %w body=%s", err, string(body))
	}
	if out.Code != 0 {
		return nil, fmt.Errorf("shaperef code=%d %s", out.Code, out.Msg)
	}
	return &out.Data, nil
}
