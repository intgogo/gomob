// Package vinrefclient —— cv-engine 调用 vin-ref 服务的 HTTP 客户端。
//
// cv-engine 在做 vin_compare_with_ref 时，需要按 (vehicle_model_id, character) 拉
// 当前 active 批次的对照样本（alpha 掩膜图）做厂家级字形比对。当前走 HTTP
// （vin-ref 服务暴露 /v1/catalog/vehicles/{vmid}/vin-refs/active/samples?character=X）；
// 后续 M-S10.6 引入 gRPC 时，本客户端可保留同样的接口形态、底层换实现。
package vinrefclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// Sample 一条字形参考样本（从 vin-ref 拉回来的）。
//
// 字段语义对齐 server/internal/vinref/handler.go 的 sampleDTO；
// 本侧只关心 cv-engine 拼对照集需要的最少子集。
type Sample struct {
	ID               string  `json:"id"`
	BatchID          string  `json:"batch_id"`
	Character        string  `json:"character"`
	ArrMode          int16   `json:"arr_mode"`
	FontID           string  `json:"font_id"`
	FontFamilyID     *string `json:"font_family_id,omitempty"`
	PositionHint     *int16  `json:"position_hint,omitempty"`
	AlphaObjectKey   string  `json:"alpha_object_key"`
	AlphaSHA256      string  `json:"alpha_sha256"`
	AlphaSizeBytes   int64   `json:"alpha_size_bytes"`
	AlphaURL         string  `json:"alpha_url,omitempty"`     // 5min 签名 URL
	AlphaURLExpireAt string  `json:"alpha_url_expire_at,omitempty"`
	QCScore          *float32 `json:"qc_score,omitempty"`
}

// Client cv-engine 侧的 vin-ref HTTP 客户端。
type Client struct {
	BaseURL string        // 例 "http://127.0.0.1:18058"
	Timeout time.Duration // 默认 5s
	HTTP    *http.Client
}

// NewClient 默认 5s 超时。
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = "http://127.0.0.1:18058"
	}
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		Timeout: 5 * time.Second,
		HTTP: &http.Client{
			Timeout: 5 * time.Second,
		},
	}
}

// 与 vin-ref handler 返回结构同步：data.{batch_id, batch_name, status, items[]}。
type listActiveResp struct {
	Code int             `json:"code"`
	Data listActiveData  `json:"data"`
	Msg  string          `json:"message,omitempty"`
}
type listActiveData struct {
	BatchID   string   `json:"batch_id"`
	BatchName string   `json:"batch_name"`
	Status    string   `json:"status"`
	Items     []Sample `json:"items"`
}

// ListActiveSamples 拉指定车型当前 active 批次下、给定字符的所有样本。
//
//	character 必须 1 个 VIN 合法字符（0-9 + A-Z 去 I/O/Q）；空字符串=不过滤
//	positionHint 0=不限；1..17 按 VIN 位置过滤
//	limit 0=默认 200
//
// 返 ErrNotFound 表示该车型当前没有 active 批次。
func (c *Client) ListActiveSamples(
	ctx context.Context, vehicleModelID int64, character string, positionHint int, limit int,
) ([]Sample, string, error) {
	if vehicleModelID <= 0 {
		return nil, "", errors.New("vehicle_model_id 必须 > 0")
	}
	q := url.Values{}
	if character != "" {
		q.Set("character", strings.ToUpper(character))
	}
	if positionHint > 0 {
		q.Set("position_hint", strconv.Itoa(positionHint))
	}
	if limit > 0 {
		q.Set("limit", strconv.Itoa(limit))
	}
	u := fmt.Sprintf("%s/v1/catalog/vehicles/%d/vin-refs/active/samples", c.BaseURL, vehicleModelID)
	if qs := q.Encode(); qs != "" {
		u += "?" + qs
	}

	rctx, cancel := context.WithTimeout(ctx, c.Timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, "", err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, "", fmt.Errorf("vinref %s: %w", u, err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4<<20))

	if resp.StatusCode == http.StatusNotFound {
		return nil, "", ErrNotFound
	}
	if resp.StatusCode != http.StatusOK {
		return nil, "", fmt.Errorf("vinref http=%d body=%s", resp.StatusCode, string(body))
	}
	var out listActiveResp
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, "", fmt.Errorf("vinref 响应解析: %w body=%s", err, string(body))
	}
	if out.Code != 0 {
		return nil, "", fmt.Errorf("vinref code=%d %s", out.Code, out.Msg)
	}
	return out.Data.Items, out.Data.BatchID, nil
}

// ErrNotFound 当指定车型还没有 published 批次时返回。
var ErrNotFound = errors.New("vinref: no active batch")

// FetchAlpha 直接拉 alpha 图字节（走签名 URL）。返回 sha256 / size 校验由 caller 做。
func (c *Client) FetchAlpha(ctx context.Context, s Sample) ([]byte, error) {
	if s.AlphaURL == "" {
		return nil, errors.New("vinref sample 缺 alpha_url")
	}
	rctx, cancel := context.WithTimeout(ctx, 30*time.Second) // 大文件容忍
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodGet, s.AlphaURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("alpha http=%d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 32<<20))
}
