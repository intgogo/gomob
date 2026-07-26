package laser

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"strings"
	"time"

	"io.gomob/server/pkg/repo"
)

const (
	// 车辆最窄已验证样本约 531mm，1% 量测预算约 5.3mm；工位外参 RMS 必须压在该预算内。
	maxProductionSiteRMSMM = 5.0
	// 角点法两标记可解，但生产标定要求至少四个公共标记，给误检和空间分布留冗余。
	minProductionSiteCommonMarkers = 4
)

// UnitAcquisitionProfile 是背景兼容性所需的单元身份、设备几何和扫描设置快照。
type UnitAcquisitionProfile struct {
	IP                 string  `json:"ip"`
	Model              string  `json:"model,omitempty"`
	Serial             string  `json:"serial,omitempty"`
	HWVersion          string  `json:"hw_version,omitempty"`
	SWVersion          string  `json:"sw_version,omitempty"`
	ScanStartDeg       float64 `json:"scan_start_deg"`
	ScanStopDeg        float64 `json:"scan_stop_deg"`
	KeepRatio          float32 `json:"keep_ratio"`
	FlipVertical       bool    `json:"flip_vertical"`
	DeviceConfigSHA256 string  `json:"device_config_sha256"`
	ScanConfigSHA256   string  `json:"scan_config_sha256"`
}

func newUnitAcquisitionProfile(ip string, info DeviceInfo, start, stop float64, keepRatio float32, flipVertical bool) UnitAcquisitionProfile {
	control := info.Control
	control.ScanStartAngle = start
	control.ScanStopAngle = stop
	return UnitAcquisitionProfile{
		IP:                 ip,
		Model:              info.Model,
		Serial:             info.SN,
		HWVersion:          info.HWVer,
		SWVersion:          info.SWVer,
		ScanStartDeg:       start,
		ScanStopDeg:        stop,
		KeepRatio:          keepRatio,
		FlipVertical:       flipVertical,
		DeviceConfigSHA256: jsonSHA256(info.Calib),
		ScanConfigSHA256: jsonSHA256(struct {
			Control      ControlSettings `json:"control"`
			KeepRatio    float32         `json:"keep_ratio"`
			FlipVertical bool            `json:"flip_vertical"`
		}{Control: control, KeepRatio: keepRatio, FlipVertical: flipVertical}),
	}
}

func (p UnitAcquisitionProfile) identityJSON() json.RawMessage {
	b, _ := json.Marshal(map[string]string{
		"ip": p.IP, "model": p.Model, "serial": p.Serial,
		"hw_version": p.HWVersion, "sw_version": p.SWVersion,
	})
	return b
}

// RegionCalibrationSnapshot 随任务固化服务端区域墙版本，避免客户端本地状态影响扫描。
type RegionCalibrationSnapshot struct {
	Set          bool       `json:"set"`
	Enabled      bool       `json:"enabled"`
	Source       string     `json:"source,omitempty"`
	SourceScanID *int64     `json:"source_scan_id,omitempty"`
	UpdatedBy    *int64     `json:"updated_by,omitempty"`
	UpdatedAt    *time.Time `json:"updated_at,omitempty"`
	PointsSHA256 string     `json:"points_sha256"`
}

func normalizedRegionDefinition(f PointRegionFilter) (PointRegionFilter, error) {
	if len(f.Points) == 0 {
		return PointRegionFilter{}, nil
	}
	validated, err := (PointRegionFilter{Enabled: true, Points: f.Points}).Normalized()
	if err != nil {
		return PointRegionFilter{}, err
	}
	return PointRegionFilter{Enabled: f.Enabled, Points: validated.Points}, nil
}

func regionDefinitionSHA256(f PointRegionFilter) (string, error) {
	normalized, err := normalizedRegionDefinition(f)
	if err != nil {
		return "", err
	}
	return jsonSHA256(normalized), nil
}

func sameRegionDefinition(a, b PointRegionFilter) bool {
	ha, errA := regionDefinitionSHA256(a)
	hb, errB := regionDefinitionSHA256(b)
	return errA == nil && errB == nil && ha == hb
}

func jsonSHA256(v any) string {
	b, _ := json.Marshal(v)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func cloudFloatSHA256(xyz []float32) string {
	h := sha256.New()
	var buf [4]byte
	for _, v := range xyz {
		binary.LittleEndian.PutUint32(buf[:], math.Float32bits(v))
		_, _ = h.Write(buf[:])
	}
	return hex.EncodeToString(h.Sum(nil))
}

// backgroundRevisionCompatibility 判定背景是否可与本次采集相减。
// 新背景严格校验 site/region/设备/扫描配置；历史 fused 背景保留修改前已验证的融合云相减路径，
// 只允许同一 A/B 工位读取明确的 legacy 对象，不能冒充 A/B revision。
func backgroundRevisionCompatibility(
	rev *repo.LaserBackgroundRevision,
	siteRevision string,
	regionRevision string,
	unitA, unitB UnitAcquisitionProfile,
) (bool, string) {
	if rev == nil {
		return false, "not_set"
	}
	if rev.UnitAIP != unitA.IP || rev.UnitBIP != unitB.IP {
		return false, "station_changed"
	}
	if rev.CoordinateSchema == repo.LaserBackgroundSchemaLegacyFused {
		return false, "legacy_fused_unverified"
	}
	if rev.CoordinateSchema == repo.LaserBackgroundSchemaLegacyVerifiedFused {
		if rev.LegacyFusedObjectKey == nil || strings.TrimSpace(*rev.LegacyFusedObjectKey) == "" {
			return false, "legacy_fused_object_missing"
		}
		if !stringPtrEquals(rev.CompatibilitySite, siteRevision) {
			return false, "site_calibration_changed"
		}
		if !stringPtrEquals(rev.CompatibilityRegion, regionRevision) {
			return false, "region_calibration_changed"
		}
		if rev.LegacyFusedPoints <= 0 || rev.LegacyFusedChecksum == nil || strings.TrimSpace(*rev.LegacyFusedChecksum) == "" || len(rev.CompatibilityEvidence) == 0 {
			return false, "revision_metadata_incomplete"
		}
		var evidence struct {
			BindingVersion      int             `json:"binding_version"`
			PipelineRevision    string          `json:"pipeline_revision"`
			SourceRevisionID    int64           `json:"source_revision_id"`
			SourceScanID        int64           `json:"source_scan_id"`
			SourceFusedPoints   int64           `json:"source_fused_points"`
			SourceFusedSHA256   string          `json:"source_fused_xyz_sha256"`
			ReferenceScans      json.RawMessage `json:"reference_scans"`
			AuditReportSHA256   string          `json:"audit_report_sha256"`
			HarnessReportSHA256 string          `json:"harness_report_sha256"`
		}
		var references []json.RawMessage
		if err := json.Unmarshal(rev.CompatibilityEvidence, &evidence); err != nil ||
			evidence.BindingVersion != 1 ||
			evidence.PipelineRevision != repo.LaserBackgroundLegacyPipelineRevision ||
			evidence.SourceRevisionID <= 0 || evidence.SourceScanID <= 0 ||
			evidence.SourceFusedPoints != rev.LegacyFusedPoints ||
			evidence.SourceFusedSHA256 != *rev.LegacyFusedChecksum ||
			strings.TrimSpace(evidence.AuditReportSHA256) == "" ||
			strings.TrimSpace(evidence.HarnessReportSHA256) == "" ||
			json.Unmarshal(evidence.ReferenceScans, &references) != nil || len(references) == 0 {
			return false, "legacy_compatibility_evidence_invalid"
		}
		if !unitIdentityMatches(rev.UnitAIdentity, unitA) || !unitIdentityMatches(rev.UnitBIdentity, unitB) {
			return false, "device_identity_changed"
		}
		if !stringPtrEquals(rev.UnitADeviceConfigHash, unitA.DeviceConfigSHA256) ||
			!stringPtrEquals(rev.UnitBDeviceConfigHash, unitB.DeviceConfigSHA256) {
			return false, "device_calibration_changed"
		}
		if !stringPtrEquals(rev.UnitAScanConfigHash, unitA.ScanConfigSHA256) ||
			!stringPtrEquals(rev.UnitBScanConfigHash, unitB.ScanConfigSHA256) {
			return false, "scan_settings_changed"
		}
		return true, "ready"
	}
	if rev.CoordinateSchema != repo.LaserBackgroundSchemaRawUnitFramesV1 &&
		rev.CoordinateSchema != repo.LaserBackgroundSchemaRegionCroppedUnitV1 {
		return false, "unsupported_schema"
	}
	if !stringPtrEquals(rev.SiteRevision, siteRevision) {
		return false, "site_calibration_changed"
	}
	if rev.CoordinateSchema == repo.LaserBackgroundSchemaRegionCroppedUnitV1 &&
		!stringPtrEquals(rev.RegionRevision, regionRevision) {
		return false, "region_calibration_changed"
	}
	if rev.UnitAObjectKey == nil || strings.TrimSpace(*rev.UnitAObjectKey) == "" ||
		rev.UnitBObjectKey == nil || strings.TrimSpace(*rev.UnitBObjectKey) == "" {
		return false, "raw_object_missing"
	}
	if !unitIdentityMatches(rev.UnitAIdentity, unitA) || !unitIdentityMatches(rev.UnitBIdentity, unitB) {
		return false, "device_identity_changed"
	}
	if !stringPtrEquals(rev.UnitADeviceConfigHash, unitA.DeviceConfigSHA256) ||
		!stringPtrEquals(rev.UnitBDeviceConfigHash, unitB.DeviceConfigSHA256) {
		return false, "device_calibration_changed"
	}
	if !stringPtrEquals(rev.UnitAScanConfigHash, unitA.ScanConfigSHA256) ||
		!stringPtrEquals(rev.UnitBScanConfigHash, unitB.ScanConfigSHA256) {
		return false, "scan_settings_changed"
	}
	if rev.UnitAPoints <= 0 || rev.UnitBPoints <= 0 || rev.UnitAChecksum == nil || rev.UnitBChecksum == nil {
		return false, "revision_metadata_incomplete"
	}
	return true, "ready"
}

func unitIdentityMatches(raw json.RawMessage, profile UnitAcquisitionProfile) bool {
	var stored struct {
		IP        string `json:"ip"`
		Model     string `json:"model"`
		Serial    string `json:"serial"`
		HWVersion string `json:"hw_version"`
		SWVersion string `json:"sw_version"`
	}
	if err := json.Unmarshal(raw, &stored); err != nil {
		return false
	}
	return stored.IP == profile.IP && stored.Model == profile.Model && stored.Serial == profile.Serial &&
		stored.HWVersion == profile.HWVersion && stored.SWVersion == profile.SWVersion
}

func stringPtrEquals(value *string, expected string) bool {
	return value != nil && *value == expected && expected != ""
}

type productionSiteQualityState uint8

const (
	productionSiteQualityVerified productionSiteQualityState = iota
	productionSiteQualityMissingEvidence
)

type siteCalibrationQualityAccess struct {
	State          string
	Verified       bool
	OverrideReason string
	ScanEligible   bool
	Reason         string
}

func (q siteCalibrationQualityAccess) overrideEnabled() bool {
	return q.OverrideReason != ""
}

func (q siteCalibrationQualityAccess) productionEligible() bool {
	return q.Verified && !q.overrideEnabled()
}

// evaluateSiteCalibrationQuality 统一 GET 查询与 POST 起扫的质量判定，避免网页显示不可用、
// 起扫却已按精确 revision 获准的状态分裂。临时豁免只匹配 canonical site SHA256，
// 不补写或伪造历史 RMS/common_markers。
func evaluateSiteCalibrationQuality(
	rmsErrorMM *float64,
	commonMarkers *int,
	siteRevision string,
	unverifiedSiteRevision string,
) (siteCalibrationQualityAccess, error) {
	state, err := classifyProductionSiteQuality(rmsErrorMM, commonMarkers)
	if err != nil {
		return siteCalibrationQualityAccess{
			State:  "invalid",
			Reason: err.Error(),
		}, err
	}
	if state == productionSiteQualityVerified {
		return siteCalibrationQualityAccess{
			State:        "verified",
			Verified:     true,
			ScanEligible: true,
		}, nil
	}
	if unverifiedSiteRevision != "" && unverifiedSiteRevision == siteRevision {
		return siteCalibrationQualityAccess{
			State:          "override",
			OverrideReason: "legacy_missing_evidence",
			ScanEligible:   true,
			Reason:         "missing_evidence",
		}, nil
	}
	return siteCalibrationQualityAccess{
		State:  "missing_evidence",
		Reason: "missing_evidence",
	}, nil
}

// classifyProductionSiteQuality 先检查所有已提供证据是否真实达标，再判断是否仅为历史字段缺失。
// 这个顺序保证“缺一项 + 另一项真实超限”仍是硬拒绝，不得被临时豁免掩盖。
func classifyProductionSiteQuality(rmsErrorMM *float64, commonMarkers *int) (productionSiteQualityState, error) {
	if rmsErrorMM != nil {
		if math.IsNaN(*rmsErrorMM) || math.IsInf(*rmsErrorMM, 0) || *rmsErrorMM < 0 {
			return productionSiteQualityVerified, fmt.Errorf("rms_error_mm 必须是非负有限数")
		}
		if *rmsErrorMM > maxProductionSiteRMSMM {
			return productionSiteQualityVerified, fmt.Errorf("RMS %.2fmm 超过生产上限 %.2fmm", *rmsErrorMM, maxProductionSiteRMSMM)
		}
	}
	if commonMarkers != nil && *commonMarkers < minProductionSiteCommonMarkers {
		return productionSiteQualityVerified, fmt.Errorf("公共标记 %d 个，少于生产下限 %d 个", *commonMarkers, minProductionSiteCommonMarkers)
	}
	if rmsErrorMM == nil || commonMarkers == nil {
		return productionSiteQualityMissingEvidence, nil
	}
	return productionSiteQualityVerified, nil
}

func validateProductionSiteQuality(rmsErrorMM *float64, commonMarkers *int) error {
	state, err := classifyProductionSiteQuality(rmsErrorMM, commonMarkers)
	if err != nil {
		return err
	}
	if state == productionSiteQualityMissingEvidence {
		return fmt.Errorf("缺少 rms_error_mm/common_markers 质量证据")
	}
	return nil
}

func parseSiteMatrix(raw string) ([16]float64, error) {
	var payload struct {
		BToA []float64 `json:"b_to_a"`
	}
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		return [16]float64{}, fmt.Errorf("site_json 必须是合法 JSON: %w", err)
	}
	if len(payload.BToA) != 16 {
		return [16]float64{}, fmt.Errorf("site_json.b_to_a 必须包含 16 个数")
	}
	var out [16]float64
	for i, value := range payload.BToA {
		if math.IsNaN(value) || math.IsInf(value, 0) {
			return [16]float64{}, fmt.Errorf("site_json.b_to_a 不能包含非有限数")
		}
		out[i] = value
	}
	if err := validateRigidTransform(out); err != nil {
		return [16]float64{}, err
	}
	return out, nil
}

func validateRigidTransform(m [16]float64) error {
	const eps = 1e-3
	if math.Abs(m[12]) > eps || math.Abs(m[13]) > eps || math.Abs(m[14]) > eps || math.Abs(m[15]-1) > eps {
		return fmt.Errorf("site_json.b_to_a 最后一行必须为 [0,0,0,1]")
	}
	row := func(r int) [3]float64 { return [3]float64{m[r*4], m[r*4+1], m[r*4+2]} }
	dot := func(a, b [3]float64) float64 { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2] }
	r0, r1, r2 := row(0), row(1), row(2)
	for i, r := range [][3]float64{r0, r1, r2} {
		if math.Abs(dot(r, r)-1) > eps {
			return fmt.Errorf("site_json.b_to_a 旋转第 %d 行不是单位向量", i+1)
		}
	}
	if math.Abs(dot(r0, r1)) > eps || math.Abs(dot(r0, r2)) > eps || math.Abs(dot(r1, r2)) > eps {
		return fmt.Errorf("site_json.b_to_a 旋转矩阵不正交")
	}
	det := r0[0]*(r1[1]*r2[2]-r1[2]*r2[1]) -
		r0[1]*(r1[0]*r2[2]-r1[2]*r2[0]) +
		r0[2]*(r1[0]*r2[1]-r1[1]*r2[0])
	if math.Abs(det-1) > eps {
		return fmt.Errorf("site_json.b_to_a 旋转矩阵行列式必须为 +1")
	}
	return nil
}

func canonicalSiteSHA256(raw string) (string, error) {
	m, err := parseSiteMatrix(raw)
	if err != nil {
		return "", err
	}
	return jsonSHA256(m), nil
}

func sameSiteJSON(a, b string) bool {
	ha, errA := canonicalSiteSHA256(a)
	hb, errB := canonicalSiteSHA256(b)
	return errA == nil && errB == nil && ha == hb
}

// nativeSiteDisplayMatrix 把 native 未翻转米制 B→A 转成 runner 显示/测量系 mm：F*T*F。
func nativeSiteDisplayMatrix(raw string) ([16]float32, error) {
	n, err := parseSiteMatrix(raw)
	if err != nil {
		return [16]float32{}, err
	}
	d := [16]float32{
		float32(n[0]), float32(n[1]), float32(-n[2]), float32(n[3] * 1000),
		float32(n[4]), float32(n[5]), float32(-n[6]), float32(n[7] * 1000),
		float32(-n[8]), float32(-n[9]), float32(n[10]), float32(-n[11] * 1000),
		float32(n[12]), float32(n[13]), float32(-n[14]), float32(n[15]),
	}
	return d, nil
}

func matrixSlice(m [16]float32) []float32 {
	out := make([]float32, 16)
	copy(out, m[:])
	return out
}
