package asr

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"io.gomob/server/pkg/repo"
)

type Result struct {
	Text           string          `json:"text"`
	NormalizedText string          `json:"normalized_text"`
	Segments       json.RawMessage `json:"segments"`
	Confidence     *float32        `json:"confidence"`
	Engine         string          `json:"engine"`
	Model          string          `json:"model"`
	Language       string          `json:"language"`
}

type ServiceClient struct {
	cfg    Config
	mc     *minio.Client
	client *http.Client
}

func NewServiceClient(cfg Config) (*ServiceClient, error) {
	cfg = cfg.normalized()
	if cfg.ServiceURL == "" {
		return nil, errors.New("asr service url is empty")
	}
	mc, err := minio.New(cfg.MinIOEndpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.MinIOAccessKey, cfg.MinIOSecretKey, ""),
		Secure: cfg.MinIOUseSSL,
	})
	if err != nil {
		return nil, err
	}
	return &ServiceClient{
		cfg:    cfg,
		mc:     mc,
		client: &http.Client{Timeout: 2 * time.Minute},
	}, nil
}

func (c *ServiceClient) TranscribeAsset(ctx context.Context, asset *repo.InspectionAsset) (Result, error) {
	if asset == nil {
		return Result{}, errors.New("语音资产为空")
	}
	audio, err := c.readAsset(ctx, asset.ObjectKey)
	if err != nil {
		return Result{}, fmt.Errorf("读取语音资产失败: %w", err)
	}
	return c.TranscribeAudio(ctx, asset, audio)
}

func (c *ServiceClient) readAsset(ctx context.Context, objectKey string) ([]byte, error) {
	obj, err := c.mc.GetObject(ctx, c.cfg.Bucket, objectKey, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	defer obj.Close()
	audio, err := io.ReadAll(io.LimitReader(obj, c.cfg.MaxAudioBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(audio)) > c.cfg.MaxAudioBytes {
		return nil, fmt.Errorf("音频超过限制 %d bytes", c.cfg.MaxAudioBytes)
	}
	return audio, nil
}

func (c *ServiceClient) TranscribeAudio(ctx context.Context, asset *repo.InspectionAsset, audio []byte) (Result, error) {
	if asset == nil {
		return Result{}, errors.New("语音资产为空")
	}
	if int64(len(audio)) > c.cfg.MaxAudioBytes {
		return Result{}, fmt.Errorf("音频超过限制 %d bytes", c.cfg.MaxAudioBytes)
	}
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, err := mw.CreateFormFile("file", "voice.bin")
	if err != nil {
		return Result{}, err
	}
	if _, err := fw.Write(audio); err != nil {
		return Result{}, err
	}
	fields := map[string]string{
		"engine":     c.cfg.Engine,
		"model":      c.cfg.Model,
		"language":   c.cfg.Language,
		"mime":       asset.MIME,
		"asset_id":   fmt.Sprintf("%d", asset.ID),
		"object_key": asset.ObjectKey,
	}
	for k, v := range fields {
		if err := mw.WriteField(k, v); err != nil {
			return Result{}, err
		}
	}
	if err := mw.Close(); err != nil {
		return Result{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.ServiceURL+"/v1/asr/transcribe", &body)
	if err != nil {
		return Result{}, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := c.client.Do(req)
	if err != nil {
		return Result{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return Result{}, fmt.Errorf("asr http=%d body=%s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var out Result
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return Result{}, err
	}
	out.Text = strings.TrimSpace(out.Text)
	out.NormalizedText = strings.TrimSpace(out.NormalizedText)
	if out.Text == "" {
		out.Text = out.NormalizedText
	}
	if out.NormalizedText == "" {
		out.NormalizedText = out.Text
	}
	if out.Text == "" {
		return Result{}, errors.New("asr 返回空文本")
	}
	if len(out.Segments) == 0 {
		out.Segments = json.RawMessage(`[]`)
	}
	if out.Engine == "" {
		out.Engine = c.cfg.Engine
	}
	if out.Model == "" {
		out.Model = c.cfg.Model
	}
	if out.Language == "" {
		out.Language = c.cfg.Language
	}
	return out, nil
}
