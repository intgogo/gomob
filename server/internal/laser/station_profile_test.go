package laser

import (
	"math"
	"strings"
	"testing"

	"io.gomob/server/pkg/repo"
)

func TestParseSiteMatrixRequiresRigidTransform(t *testing.T) {
	if _, err := parseSiteMatrix(testSiteJSON); err != nil {
		t.Fatalf("合法刚体外参被拒绝: %v", err)
	}

	badScale := `{"b_to_a":[2,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}`
	if _, err := parseSiteMatrix(badScale); err == nil {
		t.Fatal("带缩放矩阵必须被拒绝")
	}

	reflection := `{"b_to_a":[-1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]}`
	if _, err := parseSiteMatrix(reflection); err == nil {
		t.Fatal("镜像矩阵必须被拒绝")
	}

	badBottomRow := `{"b_to_a":[1,0,0,0,0,1,0,0,0,0,1,0,0.1,0,0,1]}`
	if _, err := parseSiteMatrix(badBottomRow); err == nil {
		t.Fatal("非齐次刚体最后一行必须被拒绝")
	}
}

func TestBackgroundRevisionCompatibilityRequiresSameSiteAndRegionRevision(t *testing.T) {
	value := func(raw string) *string { return &raw }
	profileA := backgroundTestProfile("192.168.9.101")
	profileB := backgroundTestProfile("192.168.9.102")
	revision := &repo.LaserBackgroundRevision{
		UnitAIP: profileA.IP, UnitBIP: profileB.IP,
		SiteRevision: value("site-v1"), RegionRevision: value("region-v1"),
		UnitAObjectKey: value("background/a.pcd"), UnitBObjectKey: value("background/b.pcd"),
		UnitAPoints: 100, UnitBPoints: 100,
		UnitAChecksum: value("checksum-a"), UnitBChecksum: value("checksum-b"),
		UnitAIdentity: profileA.identityJSON(), UnitBIdentity: profileB.identityJSON(),
		UnitADeviceConfigHash: value(profileA.DeviceConfigSHA256),
		UnitBDeviceConfigHash: value(profileB.DeviceConfigSHA256),
		UnitAScanConfigHash:   value(profileA.ScanConfigSHA256),
		UnitBScanConfigHash:   value(profileB.ScanConfigSHA256),
		CoordinateSchema:      repo.LaserBackgroundSchemaRegionCroppedUnitV1,
	}

	if compatible, reason := backgroundRevisionCompatibility(revision, "site-v1", "region-v1", profileA, profileB); !compatible || reason != "ready" {
		t.Fatalf("同一工位外参 revision 应兼容，compatible=%v reason=%s", compatible, reason)
	}
	if compatible, reason := backgroundRevisionCompatibility(revision, "site-v2", "region-v1", profileA, profileB); compatible || reason != "site_calibration_changed" {
		t.Fatalf("工位外参变化必须使旧背景失效，compatible=%v reason=%s", compatible, reason)
	}
	if compatible, reason := backgroundRevisionCompatibility(revision, "site-v1", "region-v2", profileA, profileB); compatible || reason != "region_calibration_changed" {
		t.Fatalf("区域 revision 变化必须使裁剪背景失效，compatible=%v reason=%s", compatible, reason)
	}
}

func TestBackgroundRevisionCompatibilityKeepsLegacyFusedForSameStation(t *testing.T) {
	value := func(raw string) *string { return &raw }
	profileA := backgroundTestProfile("192.168.9.101")
	profileB := backgroundTestProfile("192.168.9.102")
	evidence := legacyTestEvidence(100, "fused-checksum")
	revision := &repo.LaserBackgroundRevision{
		UnitAIP: profileA.IP, UnitBIP: profileB.IP,
		LegacyFusedObjectKey:  value("laser-scans/background/fused.pcd"),
		LegacyFusedPoints:     100,
		LegacyFusedChecksum:   value("fused-checksum"),
		CompatibilitySite:     value("new-site"),
		CompatibilityRegion:   value("new-region"),
		CompatibilityEvidence: evidence,
		UnitAIdentity:         profileA.identityJSON(),
		UnitBIdentity:         profileB.identityJSON(),
		UnitADeviceConfigHash: value(profileA.DeviceConfigSHA256),
		UnitBDeviceConfigHash: value(profileB.DeviceConfigSHA256),
		UnitAScanConfigHash:   value(profileA.ScanConfigSHA256),
		UnitBScanConfigHash:   value(profileB.ScanConfigSHA256),
		CoordinateSchema:      repo.LaserBackgroundSchemaLegacyVerifiedFused,
	}

	if compatible, reason := backgroundRevisionCompatibility(revision, "new-site", "new-region", profileA, profileB); !compatible || reason != "ready" {
		t.Fatalf("同工位 legacy 融合背景应保留修改前兼容路径，compatible=%v reason=%s", compatible, reason)
	}
	if compatible, reason := backgroundRevisionCompatibility(revision, "other-site", "new-region", profileA, profileB); compatible || reason != "site_calibration_changed" {
		t.Fatalf("legacy 兼容绑定不得跨 site revision，compatible=%v reason=%s", compatible, reason)
	}
	if compatible, reason := backgroundRevisionCompatibility(revision, "new-site", "other-region", profileA, profileB); compatible || reason != "region_calibration_changed" {
		t.Fatalf("legacy 兼容绑定不得跨 region revision，compatible=%v reason=%s", compatible, reason)
	}
	changedProfileB := profileB
	changedProfileB.ScanConfigSHA256 = "changed-scan-config"
	if compatible, reason := backgroundRevisionCompatibility(revision, "new-site", "new-region", profileA, changedProfileB); compatible || reason != "scan_settings_changed" {
		t.Fatalf("legacy 兼容绑定不得跨扫描配置，compatible=%v reason=%s", compatible, reason)
	}
	otherB := backgroundTestProfile("192.168.9.103")
	if compatible, reason := backgroundRevisionCompatibility(revision, "new-site", "new-region", profileA, otherB); compatible || reason != "station_changed" {
		t.Fatalf("legacy 背景不得跨工位复用，compatible=%v reason=%s", compatible, reason)
	}
	revision.LegacyFusedObjectKey = nil
	if compatible, reason := backgroundRevisionCompatibility(revision, "new-site", "new-region", profileA, profileB); compatible || reason != "legacy_fused_object_missing" {
		t.Fatalf("legacy 背景缺对象必须拒绝，compatible=%v reason=%s", compatible, reason)
	}
	unverified := *revision
	unverified.LegacyFusedObjectKey = value("laser-scans/background/fused.pcd")
	unverified.CoordinateSchema = repo.LaserBackgroundSchemaLegacyFused
	if compatible, reason := backgroundRevisionCompatibility(&unverified, "new-site", "new-region", profileA, profileB); compatible || reason != "legacy_fused_unverified" {
		t.Fatalf("无兼容绑定的 legacy 背景必须拒绝，compatible=%v reason=%s", compatible, reason)
	}
}

func TestValidateProductionSiteQuality(t *testing.T) {
	rms := 3.2
	markers := 6
	if err := validateProductionSiteQuality(&rms, &markers); err != nil {
		t.Fatalf("合格工位标定被拒绝: %v", err)
	}
	if err := validateProductionSiteQuality(nil, &markers); err == nil {
		t.Fatal("缺少 RMS 质量证据必须被拒绝")
	}
	highRMS := 5.1
	if err := validateProductionSiteQuality(&highRMS, &markers); err == nil {
		t.Fatal("RMS 超过 5mm 必须被拒绝")
	}
	fewMarkers := 3
	if err := validateProductionSiteQuality(&rms, &fewMarkers); err == nil {
		t.Fatal("公共标记少于 4 个必须被拒绝")
	}

	if state, err := classifyProductionSiteQuality(nil, &markers); err != nil || state != productionSiteQualityMissingEvidence {
		t.Fatalf("仅缺 RMS 应分类为缺证据，state=%v err=%v", state, err)
	}
	if _, err := classifyProductionSiteQuality(&highRMS, nil); err == nil {
		t.Fatal("缺 common 时已有 RMS 超限仍必须硬拒绝")
	}
	if _, err := classifyProductionSiteQuality(nil, &fewMarkers); err == nil {
		t.Fatal("缺 RMS 时已有 common 不足仍必须硬拒绝")
	}
	nan := math.NaN()
	if _, err := classifyProductionSiteQuality(&nan, nil); err == nil {
		t.Fatal("非有限 RMS 不得被缺证据豁免")
	}
}

func TestSiteCalibrationSnapshotQualityRequiresEvidence(t *testing.T) {
	legacy := SiteCalibrationSnapshot{MatrixSHA256: "site-revision"}
	if legacy.qualityVerified() || legacy.productionEligible() || !legacy.qualityUnverified() {
		t.Fatalf("仅有 revision 的旧快照不得伪标为生产可用: %+v", legacy)
	}
	rms := 3.2
	markers := 6
	verifiedLegacy := SiteCalibrationSnapshot{
		MatrixSHA256: "site-revision", RMSErrorMM: &rms, CommonMarkers: &markers,
	}
	if !verifiedLegacy.qualityVerified() || !verifiedLegacy.productionEligible() || verifiedLegacy.qualityUnverified() {
		t.Fatalf("质量证据达标的旧快照应可追认: %+v", verifiedLegacy)
	}
}

func TestEvaluateSiteCalibrationQualityStateMatrix(t *testing.T) {
	revision := strings.Repeat("a", 64)
	rms := 3.2
	markers := 6
	verified, err := evaluateSiteCalibrationQuality(&rms, &markers, revision, "")
	if err != nil || verified.State != "verified" || !verified.Verified || !verified.ScanEligible || !verified.productionEligible() {
		t.Fatalf("达标证据状态错误: %+v err=%v", verified, err)
	}

	override, err := evaluateSiteCalibrationQuality(nil, nil, revision, revision)
	if err != nil || override.State != "override" || override.Verified || !override.overrideEnabled() ||
		!override.ScanEligible || override.productionEligible() {
		t.Fatalf("精确 revision 豁免状态错误: %+v err=%v", override, err)
	}

	missing, err := evaluateSiteCalibrationQuality(nil, nil, revision, strings.Repeat("b", 64))
	if err != nil || missing.State != "missing_evidence" || missing.ScanEligible || missing.overrideEnabled() {
		t.Fatalf("缺证据状态错误: %+v err=%v", missing, err)
	}

	highRMS := 5.1
	invalid, err := evaluateSiteCalibrationQuality(&highRMS, nil, revision, revision)
	if err == nil || invalid.State != "invalid" || invalid.ScanEligible || invalid.overrideEnabled() {
		t.Fatalf("真实超限不得被豁免: %+v err=%v", invalid, err)
	}
}
