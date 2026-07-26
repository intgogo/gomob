// Package feedback 保存端侧/管理台截图问题反馈。
package feedback

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image/png"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	MaxBody        = 24 << 20
	MaxImage       = 12 << 20
	MaxImageSide   = 8192
	MaxImagePixels = 8 * 1024 * 1024
	maxIDAttempts  = 8
)

type Request struct {
	Title            string `json:"title"`
	Severity         string `json:"severity"`
	Category         string `json:"category"`
	PageURL          string `json:"pageUrl"`
	UserAgent        string `json:"userAgent"`
	ImageDataURL     string `json:"imageDataUrl"`
	AnnotatedDataURL string `json:"annotatedDataUrl"`
	Boxes            []Box  `json:"boxes"`
}

type Box struct {
	X      float64 `json:"x"`
	Y      float64 `json:"y"`
	W      float64 `json:"w"`
	H      float64 `json:"h"`
	Note   string  `json:"note"`
	Points []Point `json:"points,omitempty"`
}

type Point struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
}

type Response struct {
	OK bool   `json:"ok"`
	ID string `json:"id"`
}

type Submitter struct {
	UserID     int64
	RemoteAddr string
}

type ValidationError struct {
	Message string
}

func (e ValidationError) Error() string { return e.Message }

type Store struct {
	Dir       string
	Now       func() time.Time
	Rand      io.Reader
	writeFile func(path string, data []byte) error
}

func (s Store) Submit(req Request, submitter Submitter) (Response, error) {
	now := s.Now
	if now == nil {
		now = time.Now
	}
	if strings.TrimSpace(s.Dir) == "" {
		return Response{}, fmt.Errorf("反馈目录未配置")
	}
	cleaned, err := sanitize(req)
	if err != nil {
		return Response{}, err
	}
	shot, err := decodePNGDataURL(cleaned.ImageDataURL, MaxImage)
	if err != nil {
		return Response{}, ValidationError{Message: "截图无效: " + err.Error()}
	}
	annotated := shot
	if cleaned.AnnotatedDataURL != "" {
		annotated, err = decodePNGDataURL(cleaned.AnnotatedDataURL, MaxImage)
		if err != nil {
			return Response{}, ValidationError{Message: "标注截图无效: " + err.Error()}
		}
	}
	if annotated.Width != shot.Width || annotated.Height != shot.Height {
		return Response{}, ValidationError{Message: "标注截图尺寸必须与原截图一致"}
	}

	createdAt := now()
	if err := os.MkdirAll(s.Dir, 0o700); err != nil {
		return Response{}, fmt.Errorf("创建反馈目录失败: %w", err)
	}
	if err := os.Chmod(s.Dir, 0o700); err != nil {
		return Response{}, fmt.Errorf("设置反馈目录权限失败: %w", err)
	}
	id, stagingDir, finalDir, err := s.createStagingDir()
	if err != nil {
		return Response{}, err
	}
	committed := false
	defer func() {
		if !committed {
			_ = os.RemoveAll(stagingDir)
		}
	}()

	report := map[string]any{
		"id":               id,
		"created_at":       createdAt.Format(time.RFC3339),
		"title":            cleaned.Title,
		"severity":         cleaned.Severity,
		"category":         cleaned.Category,
		"page_url":         cleaned.PageURL,
		"user_agent":       cleaned.UserAgent,
		"reporter_user_id": submitter.UserID,
		"remote_addr":      submitter.RemoteAddr,
		"boxes":            cleaned.Boxes,
	}
	raw, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return Response{}, fmt.Errorf("生成反馈 JSON 失败: %w", err)
	}
	files := []struct {
		name string
		data []byte
	}{
		{name: "screenshot.png", data: shot.Raw},
		{name: "annotated.png", data: annotated.Raw},
		{name: "report.json", data: raw},
		{name: "report.md", data: []byte(markdown(id, cleaned, submitter))},
	}
	writeFile := s.writeFile
	if writeFile == nil {
		writeFile = writeFileSynced
	}
	for _, file := range files {
		if err := writeFile(filepath.Join(stagingDir, file.name), file.data); err != nil {
			return Response{}, fmt.Errorf("保存反馈文件 %s 失败: %w", file.name, err)
		}
	}
	if err := syncDir(stagingDir); err != nil {
		return Response{}, fmt.Errorf("同步反馈临时目录失败: %w", err)
	}
	if err := os.Rename(stagingDir, finalDir); err != nil {
		return Response{}, fmt.Errorf("发布反馈目录失败: %w", err)
	}
	committed = true
	if err := syncDir(s.Dir); err != nil {
		_ = os.RemoveAll(finalDir)
		_ = syncDir(s.Dir)
		return Response{}, fmt.Errorf("同步反馈根目录失败: %w", err)
	}
	return Response{OK: true, ID: id}, nil
}

func DecodeHTTP(w http.ResponseWriter, r *http.Request) (Request, error) {
	r.Body = http.MaxBytesReader(w, r.Body, MaxBody)
	defer r.Body.Close()
	var req Request
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(&req); err != nil {
		return Request{}, ValidationError{Message: "反馈 JSON 解析失败: " + err.Error()}
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		return Request{}, ValidationError{Message: "反馈 JSON 只能包含一个对象"}
	}
	return req, nil
}

func sanitize(req Request) (Request, error) {
	req.Title = strings.TrimSpace(req.Title)
	if req.Title == "" {
		return req, ValidationError{Message: "反馈标题不能为空"}
	}
	if len([]rune(req.Title)) > 120 {
		return req, ValidationError{Message: "反馈标题过长"}
	}
	req.Severity = enumOr(req.Severity, "medium", "high", "medium", "low")
	req.Category = enumOr(req.Category, "function", "function", "data", "ui", "perf", "other")
	req.PageURL = trimRunes(req.PageURL, 600)
	req.UserAgent = trimRunes(req.UserAgent, 600)
	if req.ImageDataURL == "" {
		return req, ValidationError{Message: "缺少截图"}
	}
	if len(req.Boxes) == 0 {
		return req, ValidationError{Message: "请至少标注一个问题区域"}
	}
	if len(req.Boxes) > 50 {
		return req, ValidationError{Message: "标注数量过多"}
	}
	for i := range req.Boxes {
		b := &req.Boxes[i]
		if !finite(b.X) || !finite(b.Y) || !finite(b.W) || !finite(b.H) ||
			b.X < 0 || b.Y < 0 || b.W <= 0 || b.H <= 0 || b.X+b.W > 1.01 || b.Y+b.H > 1.01 {
			return req, ValidationError{Message: fmt.Sprintf("第 %d 个标注框坐标无效", i+1)}
		}
		b.X = clamp01(b.X)
		b.Y = clamp01(b.Y)
		b.W = clamp01(b.W)
		b.H = clamp01(b.H)
		b.Note = trimRunes(strings.TrimSpace(b.Note), 500)
		if b.Note == "" {
			return req, ValidationError{Message: fmt.Sprintf("第 %d 个标注缺少反馈内容", i+1)}
		}
		if len(b.Points) > 512 {
			return req, ValidationError{Message: fmt.Sprintf("第 %d 个圈画点数过多", i+1)}
		}
		for j := range b.Points {
			p := &b.Points[j]
			if !finite(p.X) || !finite(p.Y) || p.X < 0 || p.X > 1 || p.Y < 0 || p.Y > 1 {
				return req, ValidationError{Message: fmt.Sprintf("第 %d 个圈画的第 %d 个点坐标无效", i+1, j+1)}
			}
			p.X = clamp01(p.X)
			p.Y = clamp01(p.Y)
		}
	}
	return req, nil
}

type decodedPNG struct {
	Raw    []byte
	Width  int
	Height int
}

func decodePNGDataURL(dataURL string, maxBytes int) (decodedPNG, error) {
	const prefix = "data:image/png;base64,"
	if !strings.HasPrefix(dataURL, prefix) {
		return decodedPNG{}, fmt.Errorf("只接受 PNG data URL")
	}
	encoded := dataURL[len(prefix):]
	if len(encoded) > maxBytes*4/3+1024 {
		return decodedPNG{}, fmt.Errorf("图片超过大小限制")
	}
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return decodedPNG{}, err
	}
	if len(raw) > maxBytes {
		return decodedPNG{}, fmt.Errorf("图片超过大小限制")
	}
	configReader := bytes.NewReader(raw)
	config, err := png.DecodeConfig(configReader)
	if err != nil {
		return decodedPNG{}, fmt.Errorf("不是有效 PNG: %w", err)
	}
	if config.Width <= 0 || config.Height <= 0 || config.Width > MaxImageSide || config.Height > MaxImageSide {
		return decodedPNG{}, fmt.Errorf("图片尺寸超过限制")
	}
	if int64(config.Width)*int64(config.Height) > MaxImagePixels {
		return decodedPNG{}, fmt.Errorf("图片像素数超过限制")
	}
	decodeReader := bytes.NewReader(raw)
	if _, err := png.Decode(decodeReader); err != nil {
		return decodedPNG{}, fmt.Errorf("PNG 数据损坏: %w", err)
	}
	if decodeReader.Len() != 0 {
		return decodedPNG{}, fmt.Errorf("PNG 末尾包含多余数据")
	}
	return decodedPNG{Raw: raw, Width: config.Width, Height: config.Height}, nil
}

func (s Store) createStagingDir() (id string, stagingDir string, finalDir string, err error) {
	random := s.Rand
	if random == nil {
		random = rand.Reader
	}
	for attempt := 0; attempt < maxIDAttempts; attempt++ {
		id, err = newFeedbackID(random)
		if err != nil {
			return "", "", "", err
		}
		finalDir = filepath.Join(s.Dir, id)
		if _, statErr := os.Lstat(finalDir); statErr == nil {
			continue
		} else if !os.IsNotExist(statErr) {
			return "", "", "", fmt.Errorf("检查反馈目录失败: %w", statErr)
		}
		stagingDir = filepath.Join(s.Dir, "."+id+".tmp")
		if mkdirErr := os.Mkdir(stagingDir, 0o700); mkdirErr == nil {
			return id, stagingDir, finalDir, nil
		} else if os.IsExist(mkdirErr) {
			continue
		} else {
			return "", "", "", fmt.Errorf("创建反馈临时目录失败: %w", mkdirErr)
		}
	}
	return "", "", "", fmt.Errorf("生成唯一反馈 ID 失败")
}

func newFeedbackID(random io.Reader) (string, error) {
	var raw [16]byte
	if _, err := io.ReadFull(random, raw[:]); err != nil {
		return "", fmt.Errorf("生成反馈 ID 失败: %w", err)
	}
	return "fb_" + hex.EncodeToString(raw[:]), nil
}

func writeFileSynced(path string, data []byte) error {
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	closed := false
	defer func() {
		if !closed {
			_ = file.Close()
		}
	}()
	written, err := file.Write(data)
	if err != nil {
		return err
	}
	if written != len(data) {
		return io.ErrShortWrite
	}
	if err := file.Sync(); err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	closed = true
	return nil
}

func syncDir(path string) error {
	dir, err := os.Open(path)
	if err != nil {
		return err
	}
	defer dir.Close()
	return dir.Sync()
}

func markdown(id string, req Request, submitter Submitter) string {
	var b strings.Builder
	fmt.Fprintf(&b, "# %s\n\n", req.Title)
	fmt.Fprintf(&b, "- id: `%s`\n", id)
	fmt.Fprintf(&b, "- severity: `%s`\n", req.Severity)
	fmt.Fprintf(&b, "- category: `%s`\n", req.Category)
	fmt.Fprintf(&b, "- page: `%s`\n", req.PageURL)
	fmt.Fprintf(&b, "- user_agent: `%s`\n\n", req.UserAgent)
	fmt.Fprintf(&b, "- reporter_user_id: `%d`\n\n", submitter.UserID)
	b.WriteString("## 标注\n\n")
	for i, box := range req.Boxes {
		fmt.Fprintf(&b, "%d. x=%.4f y=%.4f w=%.4f h=%.4f path_points=%d\n\n   %s\n", i+1, box.X, box.Y, box.W, box.H, len(box.Points), box.Note)
	}
	return b.String()
}

func enumOr(v string, def string, allowed ...string) string {
	v = strings.TrimSpace(v)
	for _, item := range allowed {
		if v == item {
			return v
		}
	}
	return def
}

func trimRunes(v string, max int) string {
	r := []rune(strings.TrimSpace(v))
	if len(r) <= max {
		return string(r)
	}
	return string(r[:max])
}

func clamp01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

func finite(v float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0)
}
