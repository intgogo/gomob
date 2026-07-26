// Package modelregistryclient —— cv-engine 调 model-registry 拉 active 模型元数据。
//
// M-S10.4 链路：
//
//	cvengine 启动 → modelregistry GET /v1/models/active?name=VMASK
//	            → 拿 asset_uri + sha256 + runtime + metadata
//	            → 直拉 MinIO（loader 包里做）
//	            → core.RegisterMaskONNX / RegisterONNX
package modelregistryclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// ActiveModel 与 modelregistry handler 的 modelDTO 字段一致。
type ActiveModel struct {
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Version   string          `json:"version"`
	AssetURI  string          `json:"asset_uri"`
	SHA256    string          `json:"sha256"`
	Runtime   string          `json:"runtime"`
	Framework *string         `json:"framework,omitempty"`
	Metadata  json.RawMessage `json:"metadata,omitempty"`
	Status    string          `json:"status"`
	UpdatedAt string          `json:"updated_at"`
}

// Metadata cv-engine 关心的模型加载配置。
//
// 入库时 admin 需把这两个字段写进 model.metadata；缺省 cv-engine 当 general。
type Metadata struct {
	Kind    string   `json:"kind"`    // general / mask / yolo / com
	Classes []string `json:"classes"` // mask/yolo 类名
	Strides []int    `json:"strides,omitempty"`
	Anchors []int    `json:"anchors,omitempty"`
	IWidth  int      `json:"iwidth,omitempty"`
	IHeight int      `json:"iheight,omitempty"`
	IChan   int      `json:"ichan,omitempty"`
	Std     float64  `json:"std,omitempty"`
}

type Client struct {
	BaseURL string
	HTTP    *http.Client
	Timeout time.Duration
}

func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = "http://127.0.0.1:18057"
	}
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		HTTP:    &http.Client{Timeout: 10 * time.Second},
		Timeout: 10 * time.Second,
	}
}

var ErrNotFound = errors.New("modelregistry: no active version")

type respEnvelope struct {
	Code int             `json:"code"`
	Data json.RawMessage `json:"data"`
	Msg  string          `json:"message,omitempty"`
}

// GetActive 调 model-registry GET /v1/models/active?name=<name>。
func (c *Client) GetActive(ctx context.Context, name string) (*ActiveModel, error) {
	if name == "" {
		return nil, errors.New("name 必填")
	}
	u := fmt.Sprintf("%s/v1/models/active?%s", c.BaseURL, url.Values{"name": {name}}.Encode())
	rctx, cancel := context.WithTimeout(ctx, c.Timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, fmt.Errorf("modelregistry %s: %w", u, err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrNotFound
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("modelregistry http=%d body=%s", resp.StatusCode, string(body))
	}
	var env respEnvelope
	if err := json.Unmarshal(body, &env); err != nil {
		return nil, fmt.Errorf("解析失败: %w", err)
	}
	if env.Code != 0 {
		return nil, fmt.Errorf("modelregistry code=%d %s", env.Code, env.Msg)
	}
	var m ActiveModel
	if err := json.Unmarshal(env.Data, &m); err != nil {
		return nil, err
	}
	return &m, nil
}

// ParseMetadata 解析模型加载配置；空值使用 general，非法 JSON 必须显式失败。
func ParseMetadata(raw json.RawMessage) (Metadata, error) {
	var m Metadata
	if len(raw) == 0 {
		return m, nil
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		return Metadata{}, fmt.Errorf("解析 model metadata: %w", err)
	}
	return m, nil
}
