package feedback

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestStoreSubmitWritesFeedbackFiles(t *testing.T) {
	dir := t.TempDir()
	store := Store{
		Dir: dir,
		Now: func() time.Time { return time.Date(2026, 7, 1, 10, 11, 12, 0, time.UTC) },
	}
	resp, err := store.Submit(Request{
		Title:            "App 页面按钮异常",
		Severity:         "medium",
		Category:         "ui",
		PageURL:          "gomob://app/车辆外廓扫描",
		UserAgent:        "test-agent",
		ImageDataURL:     tinyPNGDataURL,
		AnnotatedDataURL: tinyPNGDataURL,
		Boxes: []Box{{
			X: 0.1, Y: 0.2, W: 0.3, H: 0.4, Note: "按钮遮挡点云",
			Points: []Point{{X: 0.1, Y: 0.2}, {X: 0.4, Y: 0.2}, {X: 0.4, Y: 0.6}},
		}},
	}, Submitter{UserID: 42, RemoteAddr: "127.0.0.1:1"})
	if err != nil {
		t.Fatal(err)
	}
	if !resp.OK || resp.ID == "" {
		t.Fatalf("resp=%+v", resp)
	}
	if !regexp.MustCompile(`^fb_[0-9a-f]{32}$`).MatchString(resp.ID) {
		t.Fatalf("反馈 ID 格式异常: %s", resp.ID)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || !entries[0].IsDir() {
		t.Fatalf("反馈目录数量异常: %+v", entries)
	}
	feedbackDir := filepath.Join(dir, entries[0].Name())
	if entries[0].Name() != resp.ID {
		t.Fatalf("反馈目录=%s，期望=%s", entries[0].Name(), resp.ID)
	}
	dirInfo, err := os.Stat(feedbackDir)
	if err != nil {
		t.Fatal(err)
	}
	if dirInfo.Mode().Perm() != 0o700 {
		t.Fatalf("反馈目录权限=%o，期望 700", dirInfo.Mode().Perm())
	}
	for _, name := range []string{"screenshot.png", "annotated.png", "report.json", "report.md"} {
		info, err := os.Stat(filepath.Join(feedbackDir, name))
		if err != nil {
			t.Fatalf("缺少 %s: %v", name, err)
		}
		if info.Mode().Perm() != 0o600 {
			t.Fatalf("%s 权限=%o，期望 600", name, info.Mode().Perm())
		}
	}
	md, err := os.ReadFile(filepath.Join(feedbackDir, "report.md"))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(md), "按钮遮挡点云") {
		t.Fatalf("report.md 未包含说明: %s", string(md))
	}
	if !strings.Contains(string(md), "reporter_user_id: `42`") || !strings.Contains(string(md), "path_points=3") {
		t.Fatalf("report.md 未包含提交者或圈画点数: %s", string(md))
	}
}

func TestStoreSubmitRejectsMissingBox(t *testing.T) {
	_, err := (Store{Dir: t.TempDir()}).Submit(Request{
		Title:        "无标注",
		ImageDataURL: tinyPNGDataURL,
	}, Submitter{})
	if err == nil {
		t.Fatal("期望缺少标注时报错")
	}
	if _, ok := err.(ValidationError); !ok {
		t.Fatalf("err=%T %v", err, err)
	}
}

func TestStoreSubmitRejectsBlankNote(t *testing.T) {
	_, err := (Store{Dir: t.TempDir()}).Submit(Request{
		Title:        "空反馈内容",
		ImageDataURL: tinyPNGDataURL,
		Boxes: []Box{{
			X: 0.1, Y: 0.2, W: 0.3, H: 0.4, Note: "   ",
		}},
	}, Submitter{UserID: 1})
	if err == nil || !strings.Contains(err.Error(), "缺少反馈内容") {
		t.Fatalf("err=%v", err)
	}
}

func TestStoreSubmitRejectsInvalidPathPoint(t *testing.T) {
	_, err := (Store{Dir: t.TempDir()}).Submit(Request{
		Title:        "非法圈画",
		ImageDataURL: tinyPNGDataURL,
		Boxes: []Box{{
			X: 0.1, Y: 0.2, W: 0.3, H: 0.4, Note: "越界",
			Points: []Point{{X: 1.2, Y: 0.4}},
		}},
	}, Submitter{UserID: 1})
	if err == nil || !strings.Contains(err.Error(), "点坐标无效") {
		t.Fatalf("err=%v", err)
	}
}

func TestStoreSubmitUsesRandomIDsWhenClockIsSame(t *testing.T) {
	random := bytes.NewReader(append(bytes.Repeat([]byte{0x11}, 16), bytes.Repeat([]byte{0x22}, 16)...))
	store := Store{
		Dir:  t.TempDir(),
		Now:  func() time.Time { return time.Date(2026, 7, 1, 10, 11, 12, 0, time.UTC) },
		Rand: random,
	}
	first, err := store.Submit(validRequest(), Submitter{UserID: 1})
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.Submit(validRequest(), Submitter{UserID: 1})
	if err != nil {
		t.Fatal(err)
	}
	if first.ID == second.ID {
		t.Fatalf("固定时钟下反馈 ID 冲突: %s", first.ID)
	}
}

func TestStoreSubmitRetriesExistingIDWithoutOverwrite(t *testing.T) {
	dir := t.TempDir()
	firstID := "fb_" + strings.Repeat("00", 16)
	existing := filepath.Join(dir, firstID)
	if err := os.Mkdir(existing, 0o700); err != nil {
		t.Fatal(err)
	}
	markerPath := filepath.Join(existing, "keep")
	if err := os.WriteFile(markerPath, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	random := bytes.NewReader(append(bytes.Repeat([]byte{0x00}, 16), bytes.Repeat([]byte{0x01}, 16)...))
	resp, err := (Store{Dir: dir, Rand: random}).Submit(validRequest(), Submitter{UserID: 1})
	if err != nil {
		t.Fatal(err)
	}
	if resp.ID == firstID {
		t.Fatalf("复用了已存在 ID: %s", resp.ID)
	}
	raw, err := os.ReadFile(markerPath)
	if err != nil || string(raw) != "old" {
		t.Fatalf("旧反馈被覆盖: raw=%q err=%v", raw, err)
	}
}

func TestStoreSubmitWriteFailureLeavesNoVisibleOrTemporaryFeedback(t *testing.T) {
	dir := t.TempDir()
	writes := 0
	store := Store{
		Dir:  dir,
		Rand: bytes.NewReader(bytes.Repeat([]byte{0x33}, 16)),
		writeFile: func(path string, data []byte) error {
			writes++
			if writes == 2 {
				return fmt.Errorf("注入写失败")
			}
			return writeFileSynced(path, data)
		},
	}
	if _, err := store.Submit(validRequest(), Submitter{UserID: 1}); err == nil {
		t.Fatal("期望写入失败")
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 0 {
		t.Fatalf("失败后残留反馈目录: %+v", entries)
	}
}

func TestStoreSubmitRejectsMalformedPNG(t *testing.T) {
	req := validRequest()
	req.ImageDataURL = pngDataURL([]byte("\x89PNG\r\n\x1a\n"))
	_, err := (Store{Dir: t.TempDir()}).Submit(req, Submitter{UserID: 1})
	if err == nil || !strings.Contains(err.Error(), "不是有效 PNG") {
		t.Fatalf("err=%v", err)
	}
}

func TestStoreSubmitRejectsPNGTrailingData(t *testing.T) {
	req := validRequest()
	raw := validPNGBytes(1, 1)
	req.ImageDataURL = pngDataURL(append(raw, []byte("extra")...))
	_, err := (Store{Dir: t.TempDir()}).Submit(req, Submitter{UserID: 1})
	if err == nil || !strings.Contains(err.Error(), "多余数据") {
		t.Fatalf("err=%v", err)
	}
}

func TestStoreSubmitRejectsOversizedPNG(t *testing.T) {
	req := validRequest()
	req.ImageDataURL = pngDataURL(validPNGBytes(MaxImageSide+1, 1))
	_, err := (Store{Dir: t.TempDir()}).Submit(req, Submitter{UserID: 1})
	if err == nil || !strings.Contains(err.Error(), "尺寸超过限制") {
		t.Fatalf("err=%v", err)
	}
}

func TestStoreSubmitRejectsMismatchedAnnotatedSize(t *testing.T) {
	req := validRequest()
	req.ImageDataURL = pngDataURL(validPNGBytes(1, 1))
	req.AnnotatedDataURL = pngDataURL(validPNGBytes(2, 1))
	_, err := (Store{Dir: t.TempDir()}).Submit(req, Submitter{UserID: 1})
	if err == nil || !strings.Contains(err.Error(), "尺寸必须与原截图一致") {
		t.Fatalf("err=%v", err)
	}
}

func TestStoreSubmitRejectsNonFiniteCoordinates(t *testing.T) {
	req := validRequest()
	req.Boxes[0].Points = []Point{{X: math.NaN(), Y: 0.2}}
	_, err := (Store{Dir: t.TempDir()}).Submit(req, Submitter{UserID: 1})
	if err == nil || !strings.Contains(err.Error(), "点坐标无效") {
		t.Fatalf("err=%v", err)
	}
}

func validRequest() Request {
	return Request{
		Title:            "App 页面按钮异常",
		Severity:         "medium",
		Category:         "ui",
		PageURL:          "gomob://app/首页",
		UserAgent:        "test-agent",
		ImageDataURL:     tinyPNGDataURL,
		AnnotatedDataURL: tinyPNGDataURL,
		Boxes: []Box{{
			X: 0.1, Y: 0.2, W: 0.3, H: 0.4, Note: "按钮遮挡",
			Points: []Point{{X: 0.1, Y: 0.2}, {X: 0.4, Y: 0.2}, {X: 0.4, Y: 0.6}},
		}},
	}
}

func validPNGBytes(width, height int) []byte {
	img := image.NewNRGBA(image.Rect(0, 0, width, height))
	img.Set(0, 0, color.NRGBA{R: 1, G: 2, B: 3, A: 255})
	var out bytes.Buffer
	if err := png.Encode(&out, img); err != nil {
		panic(err)
	}
	return out.Bytes()
}

func pngDataURL(raw []byte) string {
	return "data:image/png;base64," + base64.StdEncoding.EncodeToString(raw)
}

var tinyPNGDataURL = pngDataURL(validPNGBytes(1, 1))
