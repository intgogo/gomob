// 模板渲染 — 用 Go text/template 把 user_template + vars → 实际 user prompt。
//
// 例：
//
//	template:  "请审核 VIN={{.vin}}，置信度={{.confidence}}"
//	vars:       {"vin":"LXBH...", "confidence":0.93}
//	output:     "请审核 VIN=LXBH...，置信度=0.93"
package llmgateway

import (
	"bytes"
	"fmt"
	"text/template"
)

// Render 渲染 user prompt。
func Render(tmplText string, vars map[string]any) (string, error) {
	if tmplText == "" {
		return "", nil
	}
	t, err := template.New("user").Option("missingkey=error").Parse(tmplText)
	if err != nil {
		return "", fmt.Errorf("template parse: %w", err)
	}
	var buf bytes.Buffer
	if err := t.Execute(&buf, vars); err != nil {
		return "", fmt.Errorf("template execute: %w", err)
	}
	return buf.String(), nil
}
