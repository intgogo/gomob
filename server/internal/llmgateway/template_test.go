package llmgateway

import (
	"strings"
	"testing"
)

func TestRender(t *testing.T) {
	out, err := Render("VIN={{.vin}}, conf={{.conf}}", map[string]any{"vin": "ABC123", "conf": 0.93})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "VIN=ABC123") || !strings.Contains(out, "conf=0.93") {
		t.Fatalf("render bad: %q", out)
	}
}

func TestRenderMissingKey(t *testing.T) {
	_, err := Render("VIN={{.vin}}", map[string]any{})
	if err == nil {
		t.Fatal("缺 key 应报错（missingkey=error 模式）")
	}
}

func TestRenderEmpty(t *testing.T) {
	out, err := Render("", nil)
	if err != nil || out != "" {
		t.Fatalf("空模板应返空串；got %q err=%v", out, err)
	}
}
