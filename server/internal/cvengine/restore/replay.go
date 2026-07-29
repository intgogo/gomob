// replay.go —— 外部视觉观测的录制与回放。
//
// 还原链的模型下沉到 gosmart 之后，vin_restore_consistency 这类权威验收门若直接依赖在线
// 服务，就会失去离线可复现性：跑不了回归、也无法把某次结论钉死到确定输入上。录制一次真实
// 响应存盘，之后按输入图像内容寻址回放，验收门重新变成纯本地确定性计算。
//
// 寻址用输入图像字节的 SHA-256：同一张图必然命中同一条录制，图变了必然 miss（而不是
// 悄悄用上别的图的观测）。回放缺条目一律报错，绝不退化成"跳过该步"或"返回空观测"——
// 那会让验收门在数据缺失时给出虚假的通过。
package restore

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// visionRecord 是一次外部调用的录制内容。
type visionRecord struct {
	Method     string         `json:"method"`
	InputSHA   string         `json:"input_sha256"`
	InputBytes int            `json:"input_bytes"`
	Regions    []Detection    `json:"regions,omitempty"`
	Characters []CharacterBox `json:"characters,omitempty"`
}

const (
	visionMethodRegions    = "regions"
	visionMethodCharacters = "characters"
)

// RecordingVisionProvider 透传真实 provider 并把观测落盘，供后续离线回放。
type RecordingVisionProvider struct {
	inner VisionProvider
	dir   string
}

// NewRecordingVisionProvider 包装真实 provider，把每次观测写入 dir。
func NewRecordingVisionProvider(inner VisionProvider, dir string) (*RecordingVisionProvider, error) {
	if inner == nil {
		return nil, fmt.Errorf("录制需要真实视觉服务")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("创建录制目录: %w", err)
	}
	return &RecordingVisionProvider{inner: inner, dir: dir}, nil
}

// LocateVinRegions 透传并录制 VIN 区域观测。
func (p *RecordingVisionProvider) LocateVinRegions(
	ctx context.Context,
	imageBytes []byte,
) ([]Detection, error) {
	regions, err := p.inner.LocateVinRegions(ctx, imageBytes)
	if err != nil {
		return nil, err
	}
	record := visionRecord{
		Method:     visionMethodRegions,
		InputSHA:   visionInputKey(imageBytes),
		InputBytes: len(imageBytes),
		Regions:    regions,
	}
	if err := writeVisionRecord(p.dir, record); err != nil {
		return nil, err
	}
	return regions, nil
}

// DetectCharacters 透传并录制逐字符观测。
func (p *RecordingVisionProvider) DetectCharacters(
	ctx context.Context,
	imageBytes []byte,
) ([]CharacterBox, error) {
	characters, err := p.inner.DetectCharacters(ctx, imageBytes)
	if err != nil {
		return nil, err
	}
	record := visionRecord{
		Method:     visionMethodCharacters,
		InputSHA:   visionInputKey(imageBytes),
		InputBytes: len(imageBytes),
		Characters: characters,
	}
	if err := writeVisionRecord(p.dir, record); err != nil {
		return nil, err
	}
	return characters, nil
}

// ReplayVisionProvider 只从录制目录读观测，完全不触网。
type ReplayVisionProvider struct {
	dir string
}

// NewReplayVisionProvider 用录制目录构造离线视觉服务。
func NewReplayVisionProvider(dir string) (*ReplayVisionProvider, error) {
	info, err := os.Stat(dir)
	if err != nil {
		return nil, fmt.Errorf("读取录制目录: %w", err)
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("录制路径不是目录: %s", dir)
	}
	return &ReplayVisionProvider{dir: dir}, nil
}

// LocateVinRegions 回放 VIN 区域观测。
func (p *ReplayVisionProvider) LocateVinRegions(
	_ context.Context,
	imageBytes []byte,
) ([]Detection, error) {
	record, err := readVisionRecord(p.dir, visionMethodRegions, imageBytes)
	if err != nil {
		return nil, err
	}
	return record.Regions, nil
}

// DetectCharacters 回放逐字符观测。
func (p *ReplayVisionProvider) DetectCharacters(
	_ context.Context,
	imageBytes []byte,
) ([]CharacterBox, error) {
	record, err := readVisionRecord(p.dir, visionMethodCharacters, imageBytes)
	if err != nil {
		return nil, err
	}
	return record.Characters, nil
}

func visionInputKey(imageBytes []byte) string {
	sum := sha256.Sum256(imageBytes)
	return hex.EncodeToString(sum[:])
}

func visionRecordPath(dir, method string, imageBytes []byte) string {
	return filepath.Join(dir, fmt.Sprintf("%s_%s.json", method, visionInputKey(imageBytes)[:16]))
}

func writeVisionRecord(dir string, record visionRecord) error {
	raw, err := json.MarshalIndent(record, "", "  ")
	if err != nil {
		return fmt.Errorf("序列化视觉录制: %w", err)
	}
	path := filepath.Join(dir, fmt.Sprintf("%s_%s.json", record.Method, record.InputSHA[:16]))
	if err := os.WriteFile(path, raw, 0o644); err != nil {
		return fmt.Errorf("写入视觉录制: %w", err)
	}
	return nil
}

func readVisionRecord(dir, method string, imageBytes []byte) (visionRecord, error) {
	path := visionRecordPath(dir, method, imageBytes)
	raw, err := os.ReadFile(path)
	if err != nil {
		return visionRecord{}, fmt.Errorf(
			"视觉录制缺失（%s，输入 %d 字节）；先用 VIN_VISION_RECORD_DIR 录一次: %w",
			method, len(imageBytes), err,
		)
	}
	var record visionRecord
	if err := json.Unmarshal(raw, &record); err != nil {
		return visionRecord{}, fmt.Errorf("解析视觉录制 %s: %w", path, err)
	}
	// 输入内容已进 key，这里再校验一次，防止录制文件被手工改名后张冠李戴。
	if record.InputSHA != visionInputKey(imageBytes) {
		return visionRecord{}, fmt.Errorf("视觉录制 %s 的输入指纹不匹配", path)
	}
	return record, nil
}
