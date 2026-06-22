package restore

import (
	"errors"

	"io.gomob/server/internal/cvengine/gocv"
)

// Meta —— 还原结果元信息（随 PNG 一起返回，便于上游判定/记录）。
type Meta struct {
	OK         bool    `json:"ok"`
	TiltDeg    float64 `json:"tilt_deg"`
	WidthMM    float64 `json:"width_mm"`
	HeightMM   float64 `json:"height_mm"`
	ThetaDeg   float64 `json:"theta_deg"`
	InlierRate float64 `json:"inlier_rate"`
	RMS        float64 `json:"rms"`
	MedZ       float64 `json:"med_z"`
	OutW       int     `json:"out_w"`
	OutH       int     `json:"out_h"`
	NumDet     int     `json:"num_det"`
	InkRatio   float64 `json:"ink_ratio"`
}

// ErrTiltTooLarge 承印面倾角超过 MaxTiltDeg（原厂硬门，码 34）。
var ErrTiltTooLarge = errors.New("tilt 超过门限（承印面过斜）")

// ErrLowQuality 去阴影后墨水占比超 SigInkMax —— 噪声/坏采集（框偏/糊/低对比无法提字），判废让端侧重拍。
var ErrLowQuality = errors.New("还原签名噪声过大（坏采集，对准钢牌重拍）")

// Restore —— VIN 数码拓印还原主入口（端口自 restore_obb.py 主链）。
//
// 入参：
//
//	runner   KindCom 模型 runner（core.Registry 实现 RunCom）
//	tag      yolo-obb 模型 tag
//	rgb      彩色 JPEG 字节（rgb1300）
//	depth    深度原始字节（小端 u16 mm，dw×dh）
//	dw,dh    深度宽高
//	fx,fy,cx,cy  深度内参（各向异性 fx,fy）
//
// 出参：OCR 级签名 PNG 字节（前景=黑）+ Meta + err。
// tilt>70 时返回 ErrTiltTooLarge（meta.OK=false 且带 tilt 值），非系统级错误。
func Restore(
	runner comRunner, tag string,
	rgb []byte, depth []byte,
	dw, dh int, fx, fy, cx, cy float64,
) ([]byte, Meta, error) {
	if len(rgb) == 0 {
		return nil, Meta{}, errors.New("rgb 为空")
	}
	if len(depth) < dw*dh*2 {
		return nil, Meta{}, errors.New("depth 字节数与 dw*dh*2 不符")
	}

	colorBGR, err := gocv.IMDecode(rgb, gocv.IMReadColor)
	if err != nil || colorBGR.Empty() {
		return nil, Meta{}, errors.New("彩色解码失败")
	}
	defer func() { _ = colorBGR.Release() }()

	dets, err := Detect(runner, tag, colorBGR)
	if err != nil {
		return nil, Meta{}, err
	}

	kd := [4]float64{fx, fy, cx, cy}
	f, tilt, err := buildFrame(depth, dw, dh, kd, colorBGR, dets)
	if err != nil {
		return nil, Meta{TiltDeg: tilt, NumDet: len(dets)}, err
	}
	if f == nil {
		// tilt 门：废弃
		return nil, Meta{OK: false, TiltDeg: tilt, NumDet: len(dets), OutW: OutW, OutH: OutH}, ErrTiltTooLarge
	}

	rect, err := render(f)
	if err != nil {
		return nil, Meta{TiltDeg: tilt, NumDet: len(dets)}, err
	}
	defer func() { _ = rect.Release() }()

	sig, ink := signatureBinarize(rect)
	defer func() { _ = sig.Release() }()

	meta := Meta{
		TiltDeg:    f.tilt,
		WidthMM:    f.width,
		HeightMM:   f.height,
		ThetaDeg:   f.theta,
		InlierRate: f.plane.InlierRate,
		RMS:        f.plane.RMS,
		MedZ:       f.plane.MedZ,
		OutW:       OutW,
		OutH:       OutH,
		NumDet:     len(dets),
		InkRatio:   ink,
	}

	// 质量闸：墨水占比过高 = 噪声/坏采集（框偏/糊/低对比），判废让端侧重拍，不返垃圾图。
	if ink > SigInkMax {
		meta.OK = false
		return nil, meta, ErrLowQuality
	}

	png, err := gocv.IMEncode(gocv.PNGFileExt, sig)
	if err != nil {
		return nil, Meta{}, err
	}
	meta.OK = true
	return png, meta, nil
}
