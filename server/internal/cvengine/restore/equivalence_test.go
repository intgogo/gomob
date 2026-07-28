package restore

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

// byteEquivalenceBaseline 钉住「同一批观测 + 当前几何代码」的确定性输出。
//
// 模型下沉到外部算法服务后，逐字节等价不能再放在连真实服务的性能 harness 里判——
// gosmart 更新一次权重就会把门弄红，而那不是 gomob 的回归。这里改用离线回放：
// 观测固定 → 几何计算是纯本地确定性运算 → 输出必然逐字节固定。于是这个门只对
// 「几何代码被改坏」敏感，对模型版本完全免疫。
//
// 模型真的换了怎么办：重录观测（录制指纹随之改变），本文件的 baseline 必须同步重建，
// 且那是一次显式的、可审阅的基线变更，不是悄悄漂移。
type byteEquivalenceBaseline struct {
	Note                string                     `json:"note"`
	VisionRecordsSHA256 string                     `json:"vision_records_sha256"`
	Captures            map[string]captureBaseline `json:"captures"`
}

type captureBaseline struct {
	PNGSHA256 string `json:"png_sha256"`
	PNGBytes  int    `json:"png_bytes"`
}

const byteEquivalenceBaselinePath = "testdata/byte_equivalence.json"

// TestRestoreByteEquivalence 用离线观测回放复算真机采集，逐字节比对基线 PNG。
// 由 tests/harness/vin_restore_consistency/run.sh 与基线同批数据一起跑。
func TestRestoreByteEquivalence(t *testing.T) {
	replayDir := os.Getenv("VIN_VISION_REPLAY_DIR")
	if replayDir == "" {
		t.Skip("未设置 VIN_VISION_REPLAY_DIR，跳过逐字节等价门")
	}
	// 重建基线的唯一正规入口：换批观测或有意变更几何输出时用它，产出可审阅的 diff。
	updating := os.Getenv("VIN_EQUIVALENCE_UPDATE_BASELINE") == "1"

	var baseline byteEquivalenceBaseline
	if !updating {
		raw, err := os.ReadFile(byteEquivalenceBaselinePath)
		if err != nil {
			t.Skipf("缺逐字节等价基线（%s），跳过：%v", byteEquivalenceBaselinePath, err)
		}
		if err := json.Unmarshal(raw, &baseline); err != nil {
			t.Fatalf("解析基线: %v", err)
		}
		if len(baseline.Captures) == 0 {
			t.Fatal("基线为空：必须至少钉住一组采集，否则这个门什么也不保证")
		}
		// 基线只在录制的那一批观测下成立；换批观测必须同步重建，不能拿旧基线比新观测。
		if got := visionRecordsFingerprint(t, replayDir); got != baseline.VisionRecordsSHA256 {
			t.Fatalf(
				"观测录制指纹与基线不符\n实际 %s\n基线 %s\n换批观测后须用 VIN_EQUIVALENCE_UPDATE_BASELINE=1 重建 %s",
				got, baseline.VisionRecordsSHA256, byteEquivalenceBaselinePath,
			)
		}
	}

	provider, err := NewReplayVisionProvider(replayDir)
	if err != nil {
		t.Fatalf("加载视觉观测录制: %v", err)
	}
	resolver := NewFactoryVinCalibrationResolverFromEnv()
	capRoot := os.Getenv("VIN_EQUIVALENCE_CAP_ROOT")
	if capRoot == "" {
		capRoot = "/root/lilw/gomob/.dev/vin_factory_bf301208/vin_captures"
	}

	var names []string
	if updating {
		names = equivalenceCaptureNames(t, capRoot)
	} else {
		for name := range baseline.Captures {
			names = append(names, name)
		}
		sort.Strings(names)
	}

	rebuilt := byteEquivalenceBaseline{
		Note: "同一批观测 + 当前几何代码的确定性输出；" +
			"由 VIN_EQUIVALENCE_UPDATE_BASELINE=1 生成，模型换版须重录观测并重建本文件",
		VisionRecordsSHA256: visionRecordsFingerprint(t, replayDir),
		Captures:            map[string]captureBaseline{},
	}

	for _, name := range names {
		capDir := filepath.Join(capRoot, name)
		png, err := restoreCaptureForEquivalence(provider, resolver, capDir)
		if err != nil {
			if updating {
				// 判废样本（空拍、tilt 超限）本就不产出 PNG，不进基线。
				t.Logf("%s 不产出 PNG，跳过入基线：%v", name, err)
				continue
			}
			t.Errorf("%s 还原失败: %v", name, err)
			continue
		}
		sum := sha256.Sum256(png)
		got := hex.EncodeToString(sum[:])
		if updating {
			rebuilt.Captures[name] = captureBaseline{PNGSHA256: got, PNGBytes: len(png)}
			t.Logf("%s 入基线 sha=%s bytes=%d", name, got[:16], len(png))
			continue
		}
		want := baseline.Captures[name]
		if got != want.PNGSHA256 || len(png) != want.PNGBytes {
			t.Errorf(
				"%s 输出与基线不逐字节等价\n实际 sha=%s bytes=%d\n基线 sha=%s bytes=%d",
				name, got, len(png), want.PNGSHA256, want.PNGBytes,
			)
			continue
		}
		t.Logf("%s 逐字节等价 sha=%s bytes=%d", name, got[:16], len(png))
	}

	if updating {
		if len(rebuilt.Captures) == 0 {
			t.Fatal("重建基线时没有任何采集产出 PNG，拒绝写出空基线")
		}
		out, err := json.MarshalIndent(rebuilt, "", "  ")
		if err != nil {
			t.Fatalf("序列化基线: %v", err)
		}
		if err := os.WriteFile(byteEquivalenceBaselinePath, append(out, '\n'), 0o644); err != nil {
			t.Fatalf("写出基线: %v", err)
		}
		t.Logf("已重建基线 %s（%d 组）", byteEquivalenceBaselinePath, len(rebuilt.Captures))
	}
}

// equivalenceCaptureNames 列出 capRoot 下所有采集目录（重建基线时用）。
func equivalenceCaptureNames(t *testing.T, capRoot string) []string {
	t.Helper()
	entries, err := os.ReadDir(capRoot)
	if err != nil {
		t.Fatalf("读取采集根目录 %s: %v", capRoot, err)
	}
	var names []string
	for _, entry := range entries {
		if entry.IsDir() {
			names = append(names, entry.Name())
		}
	}
	sort.Strings(names)
	return names
}

func restoreCaptureForEquivalence(
	provider VisionProvider,
	resolver VinCalibrationResolver,
	capDir string,
) ([]byte, error) {
	metaBytes, err := os.ReadFile(filepath.Join(capDir, "meta.json"))
	if err != nil {
		return nil, err
	}
	var meta consistencyCaptureMeta
	if err := json.Unmarshal(metaBytes, &meta); err != nil {
		return nil, err
	}
	colorWidth, colorHeight := meta.Color.EncodedW, meta.Color.EncodedH
	if colorWidth == 0 || colorHeight == 0 {
		colorWidth, colorHeight = meta.Color.W, meta.Color.H
	}
	calibration, err := resolver.ResolveVinCalibration(VinCalibrationKey{
		DepthDeviceSerial: meta.DepthDeviceSerial,
		ColorDeviceSerial: meta.ColorDeviceSerial,
		DepthWidth:        meta.Depth.W,
		DepthHeight:       meta.Depth.H,
		ColorWidth:        colorWidth,
		ColorHeight:       colorHeight,
	})
	if err != nil {
		return nil, err
	}
	rgb, err := os.ReadFile(filepath.Join(capDir, "rgb1300.jpg"))
	if err != nil {
		return nil, err
	}
	depth, err := os.ReadFile(filepath.Join(capDir, "depth.yuv"))
	if err != nil {
		return nil, err
	}
	// 基线只钉干净规范图：它才是 OCR 与一致性验收的对象，展示用的刻度尺副本不进等价门。
	result, err := Restore(
		context.Background(), provider, calibration, rgb, depth, meta.Depth.W, meta.Depth.H,
	)
	return result.PNG, err
}

// visionRecordsFingerprint 与 harness analyze.py 的 vision_records_fingerprint 同口径：
// 逐文件 sha256 按文件名排序后聚合，两侧必须一致，否则报告与测试会各说各话。
func visionRecordsFingerprint(t *testing.T, dir string) string {
	t.Helper()
	entries, err := filepath.Glob(filepath.Join(dir, "*.json"))
	if err != nil {
		t.Fatalf("扫描录制目录: %v", err)
	}
	sort.Strings(entries)
	aggregate := sha256.New()
	for _, path := range entries {
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("读取录制 %s: %v", path, err)
		}
		fileSum := sha256.Sum256(raw)
		aggregate.Write([]byte(filepath.Base(path)))
		aggregate.Write([]byte(hex.EncodeToString(fileSum[:])))
	}
	return hex.EncodeToString(aggregate.Sum(nil))
}
