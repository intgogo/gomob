package modelregistryclient

import (
	"encoding/json"
	"testing"
)

func TestParseMetadataRejectsInvalidJSON(t *testing.T) {
	if _, err := ParseMetadata(json.RawMessage(`{"kind":`)); err == nil {
		t.Fatal("非法 metadata 不能静默降级成 general")
	}
}

func TestParseMetadataReadsYoloGeometry(t *testing.T) {
	meta, err := ParseMetadata(json.RawMessage(
		`{"kind":"yolo","classes":["0"],"strides":[8],"anchors":[1,2,3,4,5,6]}`,
	))
	if err != nil {
		t.Fatal(err)
	}
	if meta.Kind != "yolo" || len(meta.Strides) != 1 || len(meta.Anchors) != 6 {
		t.Fatalf("metadata 解析错误: %+v", meta)
	}
}
