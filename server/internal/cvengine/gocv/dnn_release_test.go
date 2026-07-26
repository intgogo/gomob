//go:build linux

package gocv

import (
	"strings"
	"testing"
	"unsafe"
)

func TestRelease拒绝未知Native句柄(t *testing.T) {
	marker := 1
	net := Net{p: unsafe.Pointer(&marker)}
	err := net.Release()
	if err == nil || !strings.Contains(err.Error(), "未知推理后端") {
		t.Fatalf("未知句柄应被拒绝，实际 err=%v", err)
	}
	if net.p == nil {
		t.Fatal("拒绝析构时不得篡改未知句柄")
	}
}

func TestRelease不会把AtlasLynxi句柄交给OpenCV(t *testing.T) {
	for _, tc := range []struct {
		name string
		kind netNativeKind
	}{
		{name: "atlas", kind: netNativeAtlas},
		{name: "lynxi", kind: netNativeLynxi},
	} {
		t.Run(tc.name, func(t *testing.T) {
			marker := 1
			net := Net{p: unsafe.Pointer(&marker), nativeKind: tc.kind}
			err := net.Release()
			if err == nil || !strings.Contains(err.Error(), "缺少安全析构 API") {
				t.Fatalf("缺失 Destroy 时应明确拒绝，实际 err=%v", err)
			}
			if net.p == nil {
				t.Fatal("拒绝析构时不得把未释放句柄标成 nil")
			}
		})
	}
}

func TestRelease空句柄幂等(t *testing.T) {
	for _, kind := range []netNativeKind{
		netNativeUnknown,
		netNativeOpenCV,
		netNativeORT,
		netNativeAtlas,
		netNativeLynxi,
	} {
		net := Net{nativeKind: kind}
		if err := net.Release(); err != nil {
			t.Fatalf("空句柄 kind=%d 不应报错：%v", kind, err)
		}
		if err := net.Release(); err != nil {
			t.Fatalf("重复释放空句柄 kind=%d 不应报错：%v", kind, err)
		}
	}
}
