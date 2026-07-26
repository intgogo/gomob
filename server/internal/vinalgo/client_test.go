package vinalgo

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type recordingSigner struct {
	nanos       int64
	nanosValues []int64
	sign        string
	err         error
}

func TestNewClientFromEnvRequiresPrivateKeyFile(t *testing.T) {
	t.Setenv("GOMOB_VIN_ALGO_PRIVATE_KEY_FILE", "")
	if _, err := NewClientFromEnv(); err == nil || !strings.Contains(err.Error(), "GOMOB_VIN_ALGO_PRIVATE_KEY_FILE") {
		t.Fatalf("缺部署密钥应拒绝启动，实际 err=%v", err)
	}
}

func (s *recordingSigner) Sign(nanos int64) (string, error) {
	s.nanos = nanos
	s.nanosValues = append(s.nanosValues, nanos)
	if s.sign == "echo" {
		return fmt.Sprintf("sig-%d", nanos), s.err
	}
	return s.sign, s.err
}

func TestClientRecognizeBuildsSignedMultipartAndMapsResult(t *testing.T) {
	image := []byte("png-image")
	fixed := time.Unix(123, 456)
	signer := &recordingSigner{sign: "signed-nanos"}
	successBody := testExternalBody(
		t,
		"ABC",
		[]float64{0.8, 0.6, 0.7},
		testMoreJSON(t, "ABC", []string{
			testCharacterCropWebPBase64,
			testCharacterCropWebPBase64,
			testCharacterCropWebPBase64,
		}),
		true,
	)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/cv/ocr/v1/vin_detect" {
			t.Fatalf("请求错误：%s %s", r.Method, r.URL.Path)
		}
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			t.Fatalf("multipart 解析失败：%v", err)
		}
		if got := r.FormValue("nanos"); got != "123000000456" {
			t.Fatalf("nanos=%q", got)
		}
		if got := r.FormValue("sign"); got != "signed-nanos" {
			t.Fatalf("sign=%q", got)
		}
		if _, sent := r.MultipartForm.Value["skip_image"]; sent {
			t.Fatal("请求不应发送 skip_image，否则原厂会关闭切割图")
		}
		if got := r.FormValue("vin"); got != "" {
			t.Fatalf("不应伪造 vin 字段：%q", got)
		}
		file, _, err := r.FormFile("image_binary")
		if err != nil {
			t.Fatalf("image_binary 缺失：%v", err)
		}
		defer file.Close()
		gotImage, _ := io.ReadAll(file)
		if string(gotImage) != string(image) {
			t.Fatalf("图片不一致：%q", gotImage)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, successBody)
	}))
	defer server.Close()

	client, err := NewClient(Config{
		BaseURL: server.URL,
		Timeout: time.Second,
		Signer:  signer,
		Now:     func() time.Time { return fixed },
	})
	if err != nil {
		t.Fatal(err)
	}
	result, err := client.Recognize(context.Background(), image)
	if err != nil {
		t.Fatal(err)
	}
	if signer.nanos != fixed.UnixNano() {
		t.Fatalf("签名 nanos=%d", signer.nanos)
	}
	if result.Provider != "gosmart" || result.VIN != "ABC" || result.CharacterCount != 3 {
		t.Fatalf("结果映射错误：%+v", result)
	}
	if math.Abs(result.Confidence-0.7) > 1e-12 {
		t.Fatalf("confidence=%v，期望 0.7", result.Confidence)
	}
	if len(result.CharacterScores) != 3 {
		t.Fatalf("字符分数数量错误：%v", result.CharacterScores)
	}
	if result.InferMS != 327 || result.LogID != "log-1" {
		t.Fatalf("耗时或 log_id 错误：%+v", result)
	}
	if len(result.CharacterCrops) != 3 {
		t.Fatalf("单字符切割图数量错误：%d", len(result.CharacterCrops))
	}
	for i, crop := range result.CharacterCrops {
		if crop.Position != i+1 || crop.Character != string("ABC"[i]) ||
			crop.Image.MIMEType != "image/webp" ||
			crop.Image.Width != vinCreatorCharacterCropWidth ||
			crop.Image.Height != vinCreatorCharacterCropHeight ||
			crop.Image.DataBase64 != testCharacterCropWebPBase64 {
			t.Fatalf("第 %d 位单字符切割图映射错误：%+v", i+1, crop)
		}
	}
}

func TestClientRecognizeGeneratesFreshSignatureForEveryRequest(t *testing.T) {
	var requestCount atomic.Int64
	successBody := testExternalBody(
		t,
		"ABC",
		[]float64{0.9, 0.8, 0.7},
		testMoreJSON(t, "ABC", []string{
			testCharacterCropWebPBase64,
			testCharacterCropWebPBase64,
			testCharacterCropWebPBase64,
		}),
		false,
	)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			t.Fatal(err)
		}
		call := requestCount.Add(1)
		expectedNanos := strconv.FormatInt(call, 10)
		if got := r.FormValue("nanos"); got != expectedNanos {
			t.Fatalf("第 %d 次 nanos=%q，期望 %q", call, got, expectedNanos)
		}
		if got := r.FormValue("sign"); got != "sig-"+expectedNanos {
			t.Fatalf("第 %d 次 sign=%q", call, got)
		}
		_, _ = io.WriteString(w, successBody)
	}))
	defer server.Close()

	var clock atomic.Int64
	signer := &recordingSigner{sign: "echo"}
	client, err := NewClient(Config{
		BaseURL: server.URL,
		Signer:  signer,
		Now: func() time.Time {
			return time.Unix(0, clock.Add(1))
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if _, err := client.Recognize(context.Background(), []byte("image")); err != nil {
			t.Fatal(err)
		}
	}
	if requestCount.Load() != 2 || len(signer.nanosValues) != 2 || signer.nanosValues[0] == signer.nanosValues[1] {
		t.Fatalf("未为每次请求生成新签名：calls=%d nanos=%v", requestCount.Load(), signer.nanosValues)
	}
}

func TestClientRecognizeRejectsInvalidExternalSuccessSemantics(t *testing.T) {
	validMore := testMoreJSON(t, "ABC", []string{
		testCharacterCropWebPBase64,
		testCharacterCropWebPBase64,
		testCharacterCropWebPBase64,
	})
	tests := []struct {
		name string
		body string
	}{
		{
			name: "error_msg 非 success",
			body: `{"error_code":0,"error_msg":"detect failed","result":{"vin":[{"value":"ABC"}]}}`,
		},
		{
			name: "error_code 非零",
			body: `{"error_code":400,"error_msg":"illegal access","result":{"vin":[]}}`,
		},
		{
			name: "成功但 VIN 为空",
			body: `{"error_code":0,"error_msg":"success","result":{"vin":[{"value":""}]}}`,
		},
		{
			name: "字符分数越界",
			body: testExternalBody(t, "ABC", []float64{1.2, 0.8, 0.7}, validMore, false),
		},
		{
			name: "非法 JSON",
			body: `{"error_code":`,
		},
		{
			name: "字符分数数量与 VIN 不一致",
			body: testExternalBody(t, "ABC", []float64{0.8}, validMore, false),
		},
		{
			name: "缺少 more 时禁止回退整行图",
			body: testExternalBody(t, "ABC", []float64{0.9, 0.8, 0.7}, "", true),
		},
		{
			name: "more 不是合法 JSON",
			body: testExternalBody(t, "ABC", []float64{0.9, 0.8, 0.7}, "{", false),
		},
		{
			name: "单字符切割图数量不一致",
			body: testExternalBody(
				t,
				"ABC",
				[]float64{0.9, 0.8, 0.7},
				testMoreJSON(t, "AB", []string{testCharacterCropWebPBase64, testCharacterCropWebPBase64}),
				false,
			),
		},
		{
			name: "单字符顺序与 VIN 不一致",
			body: testExternalBody(
				t,
				"ABC",
				[]float64{0.9, 0.8, 0.7},
				testMoreJSON(t, "AXC", []string{
					testCharacterCropWebPBase64,
					testCharacterCropWebPBase64,
					testCharacterCropWebPBase64,
				}),
				false,
			),
		},
		{
			name: "单字符切割图 base64 非法",
			body: testExternalBody(
				t,
				"ABC",
				[]float64{0.9, 0.8, 0.7},
				testMoreJSON(t, "ABC", []string{"%%%", testCharacterCropWebPBase64, testCharacterCropWebPBase64}),
				false,
			),
		},
		{
			name: "单字符切割图不是 WebP",
			body: testExternalBody(
				t,
				"ABC",
				[]float64{0.9, 0.8, 0.7},
				testMoreJSON(t, "ABC", []string{"aGVsbG8=", testCharacterCropWebPBase64, testCharacterCropWebPBase64}),
				false,
			),
		},
		{
			name: "单字符切割图尺寸不是 64x128",
			body: testExternalBody(
				t,
				"ABC",
				[]float64{0.9, 0.8, 0.7},
				testMoreJSON(t, "ABC", []string{testOnePixelWebPBase64, testCharacterCropWebPBase64, testCharacterCropWebPBase64}),
				false,
			),
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = io.WriteString(w, tt.body)
			}))
			defer server.Close()
			client := mustTestClient(t, server.URL, 0)
			if _, err := client.Recognize(context.Background(), []byte("image")); err == nil {
				t.Fatal("期望严格拒绝非法成功响应")
			}
		})
	}
}

func TestClientRecognizeRejectsHTTPErrorAndRedirect(t *testing.T) {
	t.Run("非 2xx", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			http.Error(w, "bad gateway", http.StatusBadGateway)
		}))
		defer server.Close()
		client := mustTestClient(t, server.URL, 0)
		_, err := client.Recognize(context.Background(), []byte("image"))
		if err == nil || !strings.Contains(err.Error(), "HTTP 502") {
			t.Fatalf("错误=%v", err)
		}
	})

	t.Run("拒绝重定向", func(t *testing.T) {
		var targetCalls atomic.Int32
		target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			targetCalls.Add(1)
			w.WriteHeader(http.StatusOK)
		}))
		defer target.Close()
		source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, target.URL+"/cv/ocr/v1/vin_detect", http.StatusTemporaryRedirect)
		}))
		defer source.Close()
		client := mustTestClient(t, source.URL, 0)
		if _, err := client.Recognize(context.Background(), []byte("image")); err == nil {
			t.Fatal("期望拒绝重定向")
		}
		if targetCalls.Load() != 0 {
			t.Fatalf("签名请求泄漏到重定向 Host：calls=%d", targetCalls.Load())
		}
	})
}

func TestClientRecognizeTimeout(t *testing.T) {
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	client := mustTestClient(t, server.URL, 20*time.Millisecond)
	_, err := client.Recognize(context.Background(), []byte("image"))
	close(release)
	server.Close()
	if err == nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("期望 deadline exceeded，实际 %v", err)
	}
}

func TestClientRecognizeRejectsSignerFailure(t *testing.T) {
	signer := &recordingSigner{err: errors.New("sign failed")}
	client, err := NewClient(Config{BaseURL: "http://127.0.0.1:1", Signer: signer})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Recognize(context.Background(), []byte("image")); err == nil {
		t.Fatal("期望签名失败")
	}
}

func TestResultJSONContract(t *testing.T) {
	raw, err := json.Marshal(Result{
		Provider:        "gosmart",
		VIN:             "AB",
		Confidence:      0.9,
		CharacterScores: []float64{0.8, 1},
		CharacterCount:  2,
		LogID:           "log",
		InferMS:         12,
		CharacterCrops: []CharacterCrop{
			{
				Position:  1,
				Character: "A",
				Image: CropImage{
					MIMEType:   "image/webp",
					DataBase64: testCharacterCropWebPBase64,
					Width:      vinCreatorCharacterCropWidth,
					Height:     vinCreatorCharacterCropHeight,
				},
			},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, field := range []string{
		`"provider"`, `"vin"`, `"confidence"`, `"character_scores"`,
		`"character_count"`, `"log_id"`, `"infer_ms"`, `"character_crops"`,
		`"position"`, `"character"`, `"image"`, `"mime_type"`, `"data_base64"`,
		`"width"`, `"height"`,
	} {
		if !strings.Contains(string(raw), field) {
			t.Fatalf("响应缺字段 %s：%s", field, raw)
		}
	}
	if strings.Contains(string(raw), `"crop_image"`) {
		t.Fatalf("响应不应再包含整行 crop_image：%s", raw)
	}
}

func testExternalBody(
	t *testing.T,
	vin string,
	scores []float64,
	more string,
	includeWholeRow bool,
) string {
	t.Helper()
	item := map[string]any{
		"value":  vin,
		"scores": scores,
	}
	if more != "" {
		item["more"] = more
	}
	result := map[string]any{
		"vin": []any{item},
	}
	if includeWholeRow {
		result["image"] = []any{map[string]any{
			"value":      testOnePixelWebPBase64,
			"image_role": "vin_detect_image",
		}}
	}
	body, err := json.Marshal(map[string]any{
		"error_code": 0,
		"error_msg":  "success",
		"total_time": 326.59,
		"log_id":     "log-1",
		"result":     result,
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(body)
}

func testMoreJSON(t *testing.T, characters string, images []string) string {
	t.Helper()
	runes := []rune(characters)
	if len(runes) != len(images) {
		t.Fatalf("测试数据字符数 %d != 图片数 %d", len(runes), len(images))
	}
	items := make([]map[string]any, 0, len(runes))
	for i, character := range runes {
		items = append(items, map[string]any{
			"character":         string(character),
			"origin_image_data": images[i],
			"alpha_image_data":  "%%% ignored by app-facing contract %%%",
		})
	}
	raw, err := json.Marshal(items)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

const testOnePixelWebPBase64 = "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"

const testCharacterCropWebPBase64 = "UklGRhIDAABXRUJQVlA4IAYDAABQEACdASpAAIAAAAAAJaQAT279Y/Fn9qu0z8Fet/E7Zhfd/yq5E/Tt+N3qn/sf41/kBnMXzX+x/lBqNP5O8yLGZ/efyA+Bj/M+2b35fTv+o9hr+lf4j8uv7uZw/l4x1JX0nlhWCj5ztqFGwvV425m2ILVF+5dNuMwJ6AGslk7zIi9StoK4sL0hiegAAP7//5pv9Sf7BX0OTMZRf5i6M7RoJn3voJ60apUtARdTmglBLw/b+i9KdZLx9ke5JOjn+GmdDxW+v/gwzypthub9HPpnATfsTLKos2yMZmgF/iROFeEPnFaxvTMGk3Az9A08eNJT7LZS0qANd7z1BfQbl1xjWbLz6Dlo/bduP8Gp7tHXy1wVvNI2rvF5jkOCZNdacqb9Hl6zyqsVerNXPpoRx9FKI0WrP2M5tcX5i0yPBs0zU4EtvJ2kBsKdEkI9xEyhq66RlpePvWLPke5Ap5QLLx3Olz2iYbgHZA5cD1UNq/TpiQ8sm1FsHGENDpMOJRoZqqu32UkGS8VGRcg46lXOSniLfGallVU8u1iuHVGLZ3Y/nxTWPOSOZexBw3yFsxPlfSSGehk01a65dQOuG2qOTg/j6XhZF0e4rvX9ElShha7w/JHU2/GDf5VC5bj0RnvJZ/6CHn57m5EB78FUo6hmtM/TjBYdjxsuSjrNawRv+C/DU1b/V3JzD4rZ0Moc9ylqko/Z9/tXR2VzciLedcOcIkY5A6Q1gNMe1VLClzqb8+ou1o+FnRjdQpEY1jHIyIvlsnlJ0c0lp2orsqKP5Y7M9Rj1uaRPVAwmRzOF4c5a/zQWod9TezPs7HH/tm+x+TUpZwaUALzc9sw331qmNA8DVX29N4cF/BnenSEmweHzcw7B842aCwuGocw6+gTUbw1eGAAhpYctF3buFrE5g6tZsXATUwh9Z5byXqRec2eTn3CQxJnMfS59uStt+Y6UH9Bt7+D+0u//lVVZueuBMPVohD+QVpAF9O5Xb4M1sP/+bfbObmh40N6uqqUXfUR/0XuBL/sCaUGPwAA="

func mustTestClient(t *testing.T, baseURL string, timeout time.Duration) *Client {
	t.Helper()
	client, err := NewClient(Config{
		BaseURL: baseURL,
		Timeout: timeout,
		Signer:  &recordingSigner{sign: "sig"},
	})
	if err != nil {
		t.Fatal(err)
	}
	return client
}
