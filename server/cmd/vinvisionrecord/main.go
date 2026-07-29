// gomob-vinvisionrecord —— 把 VIN 还原链的外部视觉观测录制到本地，供离线回放。
//
// 为什么需要它：还原用的 VMASK 区域与 VINS 字符观测都下沉到了 gosmart，若 M4.4 一致性
// 验收门直接依赖在线服务，就再也无法离线复跑、也无法把结论钉死到确定输入上。本工具连一次
// 真实服务，把观测按输入图内容寻址存盘；此后 harness 与单测全部走回放，纯本地确定性计算。
//
// 录制必须跑完整 Restore 才能覆盖到第二次调用：VINS 吃的 probe 正射图是 Restore 内部由
// 深度平面渲染出来的中间产物，脱离 Restore 无从构造。
//
// 用法：
//
//	vinvisionrecord -caps <每行一个 cap 目录的清单> -out <录制目录>
//
// 环境变量同 cvengine：GOMOB_VIN_ALGO_BASE_URL、GOMOB_VIN_ALGO_PRIVATE_KEY_FILE、
// GOMOB_VIN_FACTORY_CALIBRATION_DIR。
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"io.gomob/server/internal/cvengine/gocv"
	"io.gomob/server/internal/cvengine/restore"
	"io.gomob/server/internal/vinalgo"
	"io.gomob/server/internal/vinvision"
)

// captureMeta 与 consistency_real_test.go 的 consistencyCaptureMeta 保持同一套字段名：
// 录制与回放必须解析出完全相同的 rig key，否则录到的观测在验收时寻址不到。
type captureMeta struct {
	DepthDeviceSerial string `json:"depthDeviceSerial"`
	ColorDeviceSerial string `json:"colorDeviceSerial"`
	Color             struct {
		W        int `json:"w"`
		H        int `json:"h"`
		EncodedW int `json:"encodedW"`
		EncodedH int `json:"encodedH"`
	} `json:"color"`
	Depth struct {
		W int `json:"w"`
		H int `json:"h"`
	} `json:"depth"`
}

func main() {
	capsPath := flag.String("caps", "", "cap 目录清单文件（每行一个目录）")
	outDir := flag.String("out", "", "录制输出目录")
	timeout := flag.Duration("timeout", 2*time.Minute, "单个 cap 的总超时")
	flag.Parse()

	if *capsPath == "" || *outDir == "" {
		fmt.Fprintln(os.Stderr, "必须提供 -caps 与 -out")
		os.Exit(2)
	}

	capDirs, err := readCapList(*capsPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "读取 cap 清单: %v\n", err)
		os.Exit(1)
	}
	if len(capDirs) == 0 {
		fmt.Fprintln(os.Stderr, "cap 清单为空")
		os.Exit(1)
	}

	client, err := vinalgo.NewClientFromEnv()
	if err != nil {
		fmt.Fprintf(os.Stderr, "外部算法客户端初始化失败: %v\n", err)
		os.Exit(1)
	}
	recorder, err := restore.NewRecordingVisionProvider(vinvision.New(client), *outDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "创建录制 provider: %v\n", err)
		os.Exit(1)
	}
	resolver := restore.NewFactoryVinCalibrationResolverFromEnv()

	var recorded, skipped int
	for _, capDir := range capDirs {
		if err := recordOne(recorder, resolver, capDir, *timeout); err != nil {
			// tilt 门与格架不可靠都是业务判废：此时 VMASK/VINS 观测已经录到盘上了，
			// 回放照样能复现同一条判废路径，不算录制失败。
			if errors.Is(err, restore.ErrTiltTooLarge) ||
				errors.Is(err, restore.ErrTextAnchorUnreliable) ||
				errors.Is(err, restore.ErrVinNotDetected) {
				fmt.Printf("录制 %s：观测已存，还原判废（%v）\n", filepath.Base(capDir), err)
				recorded++
				continue
			}
			fmt.Fprintf(os.Stderr, "录制 %s 失败: %v\n", filepath.Base(capDir), err)
			skipped++
			continue
		}
		recorded++
		fmt.Printf("录制 %s：成功\n", filepath.Base(capDir))
	}

	fmt.Printf("录制完成：%d 成功 / %d 失败，输出 %s\n", recorded, skipped, *outDir)
	if recorded == 0 {
		os.Exit(1)
	}
}

func recordOne(
	provider restore.VisionProvider,
	resolver restore.VinCalibrationResolver,
	capDir string,
	timeout time.Duration,
) error {
	metaBytes, err := os.ReadFile(filepath.Join(capDir, "meta.json"))
	if err != nil {
		return fmt.Errorf("读取 meta.json: %w", err)
	}
	var meta captureMeta
	if err := json.Unmarshal(metaBytes, &meta); err != nil {
		return fmt.Errorf("解析 meta.json: %w", err)
	}
	colorWidth, colorHeight := meta.Color.EncodedW, meta.Color.EncodedH
	if colorWidth == 0 || colorHeight == 0 {
		colorWidth, colorHeight = meta.Color.W, meta.Color.H
	}
	calibration, err := resolver.ResolveVinCalibration(restore.VinCalibrationKey{
		DepthDeviceSerial: meta.DepthDeviceSerial,
		ColorDeviceSerial: meta.ColorDeviceSerial,
		DepthWidth:        meta.Depth.W,
		DepthHeight:       meta.Depth.H,
		ColorWidth:        colorWidth,
		ColorHeight:       colorHeight,
	})
	if err != nil {
		return fmt.Errorf("解析原厂标定: %w", err)
	}
	rgb, err := os.ReadFile(filepath.Join(capDir, "rgb1300.jpg"))
	if err != nil {
		return fmt.Errorf("读取彩色: %w", err)
	}
	depth, err := os.ReadFile(filepath.Join(capDir, "depth.yuv"))
	if err != nil {
		return fmt.Errorf("读取深度: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	result, err := restore.Restore(ctx, provider, calibration, rgb, depth, meta.Depth.W, meta.Depth.H)
	if err != nil {
		return err
	}
	// 录的是干净规范图那一路：验收看的就是它，带刻度尺的展示副本不进观测。
	return recordOutputAnchor(ctx, provider, result.PNG)
}

// recordOutputAnchor 录制「最终输出图的字符格架」这一步的观测。
//
// 一致性验收除了跑 Restore，还要把 4425×600 输出按固定相似变换放回探针域再检一次字符，
// 用来判定输出是否落在同一固定坐标。那次调用发生在 Restore 之外，必须单独录，
// 否则回放时会缺条目——验收门会如实报错，而不是拿空格架给出假通过。
func recordOutputAnchor(ctx context.Context, provider restore.VisionProvider, png []byte) error {
	output, err := gocv.IMDecode(png, gocv.IMReadColor)
	if err != nil || output.Empty() {
		return fmt.Errorf("解码还原输出图: %w", err)
	}
	defer func() { _ = output.Release() }()

	canvas, _, err := restore.RenderCanonicalProbeView(output)
	if err != nil {
		return err
	}
	defer func() { _ = canvas.Release() }()

	canvasPNG, err := gocv.IMEncode(gocv.PNGFileExt, canvas)
	if err != nil {
		return fmt.Errorf("编码探针视图: %w", err)
	}
	_, err = provider.DetectCharacters(ctx, canvasPNG)
	return err
}

func readCapList(path string) ([]string, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var dirs []string
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line != "" {
			dirs = append(dirs, line)
		}
	}
	return dirs, scanner.Err()
}
