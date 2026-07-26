package laser

import "strings"

// MeasuredCoordinateSchemaUnitAWorldMMV1 表示点坐标已统一到本次最终 B→A 下的 unit A 世界系，单位 mm。
const MeasuredCoordinateSchemaUnitAWorldMMV1 = "unit_a_world_mm_v1"

// MeasuredCloudArtifact 是车辆测量点云的内容身份。对象键只定位文件，本清单用于证明客户端拿到的
// PCD 与服务端本次 MeasureFull 实际输入、工位 revision 和最终 B→A 属于同一个几何产物。
type MeasuredCloudArtifact struct {
	XYZSHA256          string `json:"xyz_sha256"`
	CoordinateSchema   string `json:"coordinate_schema"`
	SourcePoints       int    `json:"source_points"`
	SiteRevision       string `json:"site_revision,omitempty"`
	RegionRevision     string `json:"region_revision,omitempty"`
	BackgroundRevision int64  `json:"background_revision,omitempty"`
	FinalBToASHA256    string `json:"final_b_to_a_sha256"`
}

func newMeasuredCloudArtifact(
	xyz []float32,
	bToA [16]float32,
	siteRevision string,
	regionRevision string,
	backgroundRevision int64,
) MeasuredCloudArtifact {
	return MeasuredCloudArtifact{
		XYZSHA256:          cloudFloatSHA256(xyz),
		CoordinateSchema:   MeasuredCoordinateSchemaUnitAWorldMMV1,
		SourcePoints:       len(xyz) / 3,
		SiteRevision:       strings.TrimSpace(siteRevision),
		RegionRevision:     strings.TrimSpace(regionRevision),
		BackgroundRevision: backgroundRevision,
		FinalBToASHA256:    cloudFloatSHA256(bToA[:]),
	}
}

func (a MeasuredCloudArtifact) validContentIdentity() bool {
	return a.CoordinateSchema == MeasuredCoordinateSchemaUnitAWorldMMV1 &&
		a.SourcePoints > 0 &&
		strings.TrimSpace(a.SiteRevision) != "" &&
		strings.TrimSpace(a.RegionRevision) != "" &&
		isSHA256Hex(a.XYZSHA256) &&
		isSHA256Hex(a.FinalBToASHA256)
}

func isSHA256Hex(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, c := range value {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return true
}
