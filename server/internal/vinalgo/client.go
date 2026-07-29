// Package vinalgo 封装外部 VIN OCR 算法服务。
package vinalgo

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"io"
	"math"
	"mime/multipart"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/image/webp"
)

const (
	// DefaultBaseURL 是现场外部算法服务地址，可用 GOMOB_VIN_ALGO_BASE_URL 覆盖。
	DefaultBaseURL = "http://192.168.9.166:35000"
	providerName   = "gosmart"

	defaultConnectTimeout = 3 * time.Second
	// 小于 cvengine HTTP WriteTimeout(60s)，确保超时时还能向 Android 返回结构化 504。
	defaultRequestTimeout         = 50 * time.Second
	maxResponseBytes              = 2 << 20
	maxCharacterCropBytes         = 256 << 10
	maxCharacterCropTotalBytes    = 2 << 20
	maxCharacterCropCount         = 32
	vinCreatorCharacterCropWidth  = 64
	vinCreatorCharacterCropHeight = 128
)

// Signer 为外部服务生成 nanos 的 RSA-SHA1 签名。
type Signer interface {
	Sign(nanos int64) (string, error)
}

// Recognizer 是 HTTP 层依赖的 VIN OCR 能力边界。
type Recognizer interface {
	Recognize(ctx context.Context, image []byte) (Result, error)
}

// Result 是 Gomob 对外暴露的纯 OCR 结果，不包含厂家字形 verdict。
type Result struct {
	Provider        string          `json:"provider"`
	VIN             string          `json:"vin"`
	Confidence      float64         `json:"confidence"`
	CharacterScores []float64       `json:"character_scores"`
	CharacterCount  int             `json:"character_count"`
	LogID           string          `json:"log_id"`
	InferMS         int64           `json:"infer_ms"`
	CharacterCrops  []CharacterCrop `json:"character_crops"`
}

// CharacterCrop 是对外稳定的单字符切割图契约，不透传厂家内部 more/alpha 结构。
type CharacterCrop struct {
	Position  int       `json:"position"`
	Character string    `json:"character"`
	Image     CropImage `json:"image"`
}

// CropImage 是服务端已全量解码校验的单字符 WebP。
type CropImage struct {
	MIMEType   string `json:"mime_type"`
	DataBase64 string `json:"data_base64"`
	Width      int    `json:"width"`
	Height     int    `json:"height"`
}

// Config 配置外部 VIN OCR 客户端。
type Config struct {
	BaseURL        string
	ConnectTimeout time.Duration
	Timeout        time.Duration
	Signer         Signer
	HTTPClient     *http.Client
	Now            func() time.Time
}

// Client 调用 gosmart 兼容的 /cv/ocr/v1/vin_detect。
type Client struct {
	baseURL    string
	timeout    time.Duration
	signer     Signer
	httpClient *http.Client
	now        func() time.Time
}

type externalResponse struct {
	ErrorCode int            `json:"error_code"`
	ErrorMsg  string         `json:"error_msg"`
	TotalTime float64        `json:"total_time"`
	LogID     string         `json:"log_id"`
	Result    externalResult `json:"result"`
}

type externalResult struct {
	VIN []externalVINItem `json:"vin"`
}

type externalVINItem struct {
	Value  string    `json:"value"`
	Scores []float64 `json:"scores"`
	More   string    `json:"more"`
}

type externalCharacterCrop struct {
	Character       string `json:"character"`
	OriginImageData string `json:"origin_image_data"`
}

// NewClient 创建外部 VIN OCR 客户端。
func NewClient(cfg Config) (*Client, error) {
	baseURL := strings.TrimRight(strings.TrimSpace(cfg.BaseURL), "/")
	if baseURL == "" {
		baseURL = DefaultBaseURL
	}
	parsed, err := url.Parse(baseURL)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return nil, fmt.Errorf("外部 VIN 算法地址非法")
	}
	if cfg.Signer == nil {
		return nil, errors.New("外部 VIN 算法签名器未配置")
	}

	connectTimeout := cfg.ConnectTimeout
	if connectTimeout <= 0 {
		connectTimeout = defaultConnectTimeout
	}
	timeout := cfg.Timeout
	if timeout <= 0 {
		timeout = defaultRequestTimeout
	}

	var httpClient http.Client
	if cfg.HTTPClient != nil {
		httpClient = *cfg.HTTPClient
	} else {
		transport := http.DefaultTransport.(*http.Transport).Clone()
		transport.DialContext = (&net.Dialer{
			Timeout:   connectTimeout,
			KeepAlive: 30 * time.Second,
		}).DialContext
		httpClient.Transport = transport
	}
	httpClient.Timeout = timeout
	// 算法请求携带签名与图片，任何重定向都拒绝，避免凭证和数据离开配置的 Host。
	httpClient.CheckRedirect = func(_ *http.Request, _ []*http.Request) error {
		return http.ErrUseLastResponse
	}

	now := cfg.Now
	if now == nil {
		now = time.Now
	}
	return &Client{
		baseURL:    baseURL,
		timeout:    timeout,
		signer:     cfg.Signer,
		httpClient: &httpClient,
		now:        now,
	}, nil
}

// NewClientFromEnv 使用现场默认地址和服务端签名配置创建客户端。
func NewClientFromEnv() (*Client, error) {
	keyFile := strings.TrimSpace(os.Getenv("GOMOB_VIN_ALGO_PRIVATE_KEY_FILE"))
	if keyFile == "" {
		return nil, errors.New("必须设置 GOMOB_VIN_ALGO_PRIVATE_KEY_FILE；私钥不得内置或提交到仓库")
	}
	signer, err := NewRSASignerFromFile(keyFile)
	if err != nil {
		return nil, fmt.Errorf("初始化外部 VIN 算法签名器: %w", err)
	}
	return NewClient(Config{
		BaseURL:        envOr("GOMOB_VIN_ALGO_BASE_URL", DefaultBaseURL),
		ConnectTimeout: envDuration("GOMOB_VIN_ALGO_CONNECT_TIMEOUT", defaultConnectTimeout),
		Timeout:        envDuration("GOMOB_VIN_ALGO_TIMEOUT", defaultRequestTimeout),
		Signer:         signer,
	})
}

func envOr(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envDuration(name string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return parsed
}

// Recognize 把服务端权威正射图发给外部算法并严格解析结果。
func (c *Client) Recognize(ctx context.Context, image []byte) (Result, error) {
	if len(image) == 0 {
		return Result{}, errors.New("VIN 正射图为空")
	}

	nanos := c.now().UnixNano()
	sign, err := c.signer.Sign(nanos)
	if err != nil {
		return Result{}, fmt.Errorf("生成外部 VIN 算法签名: %w", err)
	}

	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	if err := writer.WriteField("nanos", strconv.FormatInt(nanos, 10)); err != nil {
		return Result{}, err
	}
	if err := writer.WriteField("sign", sign); err != nil {
		return Result{}, err
	}
	part, err := writer.CreateFormFile("image_binary", "vin.png")
	if err != nil {
		return Result{}, err
	}
	if _, err := part.Write(image); err != nil {
		return Result{}, err
	}
	if err := writer.Close(); err != nil {
		return Result{}, err
	}

	reqCtx := ctx
	var cancel context.CancelFunc
	if _, hasDeadline := ctx.Deadline(); !hasDeadline {
		reqCtx, cancel = context.WithTimeout(ctx, c.timeout)
		defer cancel()
	}
	req, err := http.NewRequestWithContext(
		reqCtx,
		http.MethodPost,
		c.baseURL+"/cv/ocr/v1/vin_detect",
		&body,
	)
	if err != nil {
		return Result{}, fmt.Errorf("构造外部 VIN 算法请求: %w", err)
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("User-Agent", "gomob-cvengine/vin-recognize")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return Result{}, fmt.Errorf("调用外部 VIN 算法: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusMultipleChoices {
		return Result{}, fmt.Errorf("外部 VIN 算法返回 HTTP %d", resp.StatusCode)
	}

	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseBytes+1))
	if err != nil {
		return Result{}, fmt.Errorf("读取外部 VIN 算法响应: %w", err)
	}
	if len(raw) > maxResponseBytes {
		return Result{}, errors.New("外部 VIN 算法响应过大")
	}
	var decoded externalResponse
	if err := json.Unmarshal(raw, &decoded); err != nil {
		return Result{}, fmt.Errorf("解析外部 VIN 算法响应: %w", err)
	}
	if decoded.ErrorCode != 0 || decoded.ErrorMsg != "success" {
		return Result{}, fmt.Errorf(
			"外部 VIN 算法失败: error_code=%d error_msg=%s",
			decoded.ErrorCode,
			strings.TrimSpace(decoded.ErrorMsg),
		)
	}

	var selected *externalVINItem
	for i := range decoded.Result.VIN {
		if strings.TrimSpace(decoded.Result.VIN[i].Value) != "" {
			selected = &decoded.Result.VIN[i]
			break
		}
	}
	if selected == nil {
		return Result{}, errors.New("外部 VIN 算法成功响应缺少 VIN")
	}
	vin := strings.ToUpper(strings.TrimSpace(selected.Value))
	vinRunes := []rune(vin)
	if len(vinRunes) == 0 || len(vinRunes) > maxCharacterCropCount {
		return Result{}, errors.New("外部 VIN 算法字符数量非法")
	}
	scores := append([]float64(nil), selected.Scores...)
	if len(scores) != len(vinRunes) {
		return Result{}, errors.New("外部 VIN 算法字符分数数量与 VIN 不一致")
	}
	confidence := 0.0
	for _, score := range scores {
		if math.IsNaN(score) || math.IsInf(score, 0) || score < 0 || score > 1 {
			return Result{}, errors.New("外部 VIN 算法字符分数越界")
		}
		confidence += score
	}
	confidence /= float64(len(scores))
	characterCrops, err := parseCharacterCrops(selected.More, vinRunes)
	if err != nil {
		return Result{}, err
	}
	inferMS := int64(math.Round(decoded.TotalTime))
	if inferMS < 0 {
		inferMS = 0
	}
	return Result{
		Provider:        providerName,
		VIN:             vin,
		Confidence:      confidence,
		CharacterScores: scores,
		CharacterCount:  len(vinRunes),
		LogID:           decoded.LogID,
		InferMS:         inferMS,
		CharacterCrops:  characterCrops,
	}, nil
}

func parseCharacterCrops(more string, vinRunes []rune) ([]CharacterCrop, error) {
	if strings.TrimSpace(more) == "" {
		return nil, errors.New("外部 VIN 算法成功响应缺少单字符切割图")
	}
	var items []externalCharacterCrop
	if err := json.Unmarshal([]byte(more), &items); err != nil {
		return nil, fmt.Errorf("解析外部 VIN 算法单字符切割图: %w", err)
	}
	if len(items) != len(vinRunes) {
		return nil, errors.New("外部 VIN 算法单字符切割图数量与 VIN 不一致")
	}
	if len(items) == 0 || len(items) > maxCharacterCropCount {
		return nil, errors.New("外部 VIN 算法单字符切割图数量非法")
	}

	result := make([]CharacterCrop, 0, len(items))
	totalBytes := 0
	for i, item := range items {
		character := strings.ToUpper(strings.TrimSpace(item.Character))
		characterRunes := []rune(character)
		if len(characterRunes) != 1 || characterRunes[0] != vinRunes[i] {
			return nil, fmt.Errorf("外部 VIN 算法第 %d 位单字符切割图与 VIN 不一致", i+1)
		}
		image, byteCount, err := parseCharacterCropImage(item.OriginImageData)
		if err != nil {
			return nil, fmt.Errorf("外部 VIN 算法第 %d 位单字符切割图: %w", i+1, err)
		}
		totalBytes += byteCount
		if totalBytes > maxCharacterCropTotalBytes {
			return nil, errors.New("外部 VIN 算法单字符切割图总大小非法")
		}
		result = append(result, CharacterCrop{
			Position:  i + 1,
			Character: character,
			Image:     image,
		})
	}
	return result, nil
}

func parseCharacterCropImage(value string) (CropImage, int, error) {
	encoded := strings.TrimSpace(value)
	if encoded == "" {
		return CropImage{}, 0, errors.New("origin_image_data 为空")
	}
	raw, err := base64.StdEncoding.Strict().DecodeString(encoded)
	if err != nil {
		return CropImage{}, 0, fmt.Errorf("base64 非法: %w", err)
	}
	if len(raw) == 0 || len(raw) > maxCharacterCropBytes {
		return CropImage{}, 0, errors.New("大小非法")
	}
	config, err := webp.DecodeConfig(bytes.NewReader(raw))
	if err != nil {
		return CropImage{}, 0, fmt.Errorf("不是有效 WebP: %w", err)
	}
	if config.Width != vinCreatorCharacterCropWidth || config.Height != vinCreatorCharacterCropHeight {
		return CropImage{}, 0, fmt.Errorf(
			"尺寸 %dx%d != %dx%d",
			config.Width,
			config.Height,
			vinCreatorCharacterCropWidth,
			vinCreatorCharacterCropHeight,
		)
	}
	decoded, err := webp.Decode(bytes.NewReader(raw))
	if err != nil {
		return CropImage{}, 0, fmt.Errorf("解码失败: %w", err)
	}
	bounds := decoded.Bounds()
	if bounds != image.Rect(0, 0, config.Width, config.Height) {
		return CropImage{}, 0, errors.New("解码尺寸不一致")
	}
	return CropImage{
		MIMEType:   "image/webp",
		DataBase64: encoded,
		Width:      config.Width,
		Height:     config.Height,
	}, len(raw), nil
}
