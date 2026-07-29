package vinalgo

import (
	"context"
	"os"
	"testing"
	"time"
)

// TestLiveExternalRecognition 仅在显式提供真图时访问现场算法服务。
func TestLiveExternalRecognition(t *testing.T) {
	imagePath := os.Getenv("GOMOB_VIN_ALGO_LIVE_IMAGE")
	if imagePath == "" {
		t.Skip("未设置 GOMOB_VIN_ALGO_LIVE_IMAGE")
	}
	image, err := os.ReadFile(imagePath)
	if err != nil {
		t.Fatal(err)
	}
	client, err := NewClientFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	result, err := client.Recognize(ctx, image)
	if err != nil {
		t.Fatal(err)
	}
	if result.VIN == "" || result.CharacterCount == 0 || len(result.CharacterCrops) != result.CharacterCount {
		t.Fatalf("外部算法未返回 VIN：provider=%s count=%d", result.Provider, result.CharacterCount)
	}
	vinRunes := []rune(result.VIN)
	for i, crop := range result.CharacterCrops {
		if crop.Position != i+1 || crop.Character != string(vinRunes[i]) ||
			crop.Image.Width != vinCreatorCharacterCropWidth ||
			crop.Image.Height != vinCreatorCharacterCropHeight {
			t.Fatalf("第 %d 位单字符切割图契约错误：position=%d size=%dx%d", i+1, crop.Position, crop.Image.Width, crop.Image.Height)
		}
	}
	// 不在测试日志打印完整 VIN。
	t.Logf(
		"外部 VIN OCR 成功：provider=%s count=%d scores=%d confidence=%.3f character_crops=%d crop_size=%dx%d infer_ms=%d",
		result.Provider,
		result.CharacterCount,
		len(result.CharacterScores),
		result.Confidence,
		len(result.CharacterCrops),
		result.CharacterCrops[0].Image.Width,
		result.CharacterCrops[0].Image.Height,
		result.InferMS,
	)
}
