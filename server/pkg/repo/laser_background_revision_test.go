package repo

import (
	"database/sql"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

type backgroundRevisionRowStub struct {
	err       error
	identityA []byte
	identityB []byte
	now       time.Time
}

func (s *backgroundRevisionRowStub) Scan(dest ...any) error {
	if s.err != nil {
		return s.err
	}
	*(dest[0].(*int64)) = 17
	*(dest[1].(*string)) = "192.168.9.101"
	*(dest[2].(*string)) = "192.168.9.102"
	_ = dest[3].(*sql.NullString).Scan("background/17/unit_a.pcd")
	_ = dest[4].(*sql.NullString).Scan(nil)
	_ = dest[5].(*sql.NullString).Scan("background/legacy/fused.pcd")
	_ = dest[6].(*sql.NullInt64).Scan(int64(207))
	_ = dest[7].(*sql.NullString).Scan("site-sha")
	_ = dest[8].(*sql.NullString).Scan("region-sha")
	*(dest[9].(*int64)) = 579
	_ = dest[10].(*sql.NullString).Scan("fused-checksum")
	_ = dest[11].(*sql.NullString).Scan("compat-site")
	_ = dest[12].(*sql.NullString).Scan("compat-region")
	*(dest[13].(*[]byte)) = []byte(`{"replay_scan_ids":[197,207]}`)
	*(dest[14].(*int64)) = 123
	*(dest[15].(*int64)) = 456
	_ = dest[16].(*sql.NullString).Scan("checksum-a")
	_ = dest[17].(*sql.NullString).Scan(nil)
	*(dest[18].(*[]byte)) = s.identityA
	*(dest[19].(*[]byte)) = s.identityB
	_ = dest[20].(*sql.NullString).Scan("device-a")
	_ = dest[21].(*sql.NullString).Scan("device-b")
	_ = dest[22].(*sql.NullString).Scan("scan-a")
	_ = dest[23].(*sql.NullString).Scan("scan-b")
	*(dest[24].(*string)) = "legacy_verified_region_fused_v1"
	_ = dest[25].(*sql.NullInt64).Scan(nil)
	*(dest[26].(*time.Time)) = s.now
	*(dest[27].(*bool)) = true
	*(dest[28].(*time.Time)) = s.now.Add(time.Second)
	return nil
}

func TestScanLaserBackgroundRevisionNullableAndCopiesJSON(t *testing.T) {
	now := time.Date(2026, 7, 11, 12, 0, 0, 0, time.UTC)
	row := &backgroundRevisionRowStub{
		now:       now,
		identityA: []byte(`{"ip":"192.168.9.101"}`),
		identityB: []byte(`{"ip":"192.168.9.102"}`),
	}
	var got LaserBackgroundRevision
	if err := scanLaserBackgroundRevision(row, &got); err != nil {
		t.Fatalf("scanLaserBackgroundRevision: %v", err)
	}
	if got.ID != 17 || got.SourceScanID == nil || *got.SourceScanID != 207 {
		t.Fatalf("主键/来源扫描错误: %+v", got)
	}
	if got.SiteRevision == nil || *got.SiteRevision != "site-sha" {
		t.Fatalf("工位外参 revision 错误: %#v", got.SiteRevision)
	}
	if got.RegionRevision == nil || *got.RegionRevision != "region-sha" {
		t.Fatalf("区域 revision 错误: %#v", got.RegionRevision)
	}
	if got.LegacyFusedPoints != 579 || got.LegacyFusedChecksum == nil || *got.LegacyFusedChecksum != "fused-checksum" ||
		got.CompatibilitySite == nil || *got.CompatibilitySite != "compat-site" ||
		got.CompatibilityRegion == nil || *got.CompatibilityRegion != "compat-region" {
		t.Fatalf("legacy 兼容绑定扫描错误: %+v", got)
	}
	if got.UnitAObjectKey == nil || *got.UnitAObjectKey != "background/17/unit_a.pcd" {
		t.Fatalf("unit A 对象键错误: %#v", got.UnitAObjectKey)
	}
	if got.UnitBObjectKey != nil || got.UnitBChecksum != nil || got.CapturedBy != nil {
		t.Fatalf("NULL 字段未保持 nil: unit_b=%#v checksum=%#v captured_by=%#v",
			got.UnitBObjectKey, got.UnitBChecksum, got.CapturedBy)
	}
	row.identityA[0] = '!'
	if string(got.UnitAIdentity) != `{"ip":"192.168.9.101"}` {
		t.Fatalf("identity 未独立复制: %s", got.UnitAIdentity)
	}
	if !got.Active || !got.CapturedAt.Equal(now) || !got.CreatedAt.Equal(now.Add(time.Second)) {
		t.Fatalf("状态/时间错误: active=%v captured=%s created=%s", got.Active, got.CapturedAt, got.CreatedAt)
	}
}

func TestScanLaserBackgroundRevisionPropagatesError(t *testing.T) {
	want := errors.New("scan failed")
	var got LaserBackgroundRevision
	if err := scanLaserBackgroundRevision(&backgroundRevisionRowStub{err: want}, &got); !errors.Is(err, want) {
		t.Fatalf("应透传 scan 错误，得 %v", err)
	}
}

func TestValidateLaserBackgroundRevision(t *testing.T) {
	value := func(raw string) *string { return &raw }
	valid := LaserBackgroundRevision{
		UnitAIP:               "192.168.9.101",
		UnitBIP:               "192.168.9.102",
		SiteRevision:          value("site-sha"),
		RegionRevision:        value("region-sha"),
		UnitAObjectKey:        value("background/1/a.pcd"),
		UnitBObjectKey:        value("background/1/b.pcd"),
		UnitAPoints:           100,
		UnitBPoints:           200,
		UnitAChecksum:         value("checksum-a"),
		UnitBChecksum:         value("checksum-b"),
		UnitAIdentity:         []byte(`{"ip":"192.168.9.101"}`),
		UnitBIdentity:         []byte(`{"ip":"192.168.9.102"}`),
		UnitADeviceConfigHash: value("device-a"),
		UnitBDeviceConfigHash: value("device-b"),
		UnitAScanConfigHash:   value("scan-a"),
		UnitBScanConfigHash:   value("scan-b"),
		CoordinateSchema:      LaserBackgroundSchemaRegionCroppedUnitV1,
	}
	if err := validateLaserBackgroundRevision(valid); err != nil {
		t.Fatalf("合法区域裁剪 revision 被拒绝: %v", err)
	}

	missingSiteRevision := valid
	missingSiteRevision.SiteRevision = nil
	if err := validateLaserBackgroundRevision(missingSiteRevision); err == nil {
		t.Fatal("缺少工位外参 revision 的 A/B revision 必须被拒绝")
	}

	missingRegionRevision := valid
	missingRegionRevision.RegionRevision = nil
	if err := validateLaserBackgroundRevision(missingRegionRevision); err == nil {
		t.Fatal("缺少区域 revision 的裁剪背景必须被拒绝")
	}

	missingChecksum := valid
	missingChecksum.UnitBChecksum = nil
	if err := validateLaserBackgroundRevision(missingChecksum); err == nil {
		t.Fatal("缺少校验和的 raw revision 必须被拒绝")
	}

	wrongIdentity := valid
	wrongIdentity.UnitBIdentity = []byte(`{"ip":"192.168.9.201"}`)
	if err := validateLaserBackgroundRevision(wrongIdentity); err == nil {
		t.Fatal("设备 identity 与工位不一致必须被拒绝")
	}

	legacy := LaserBackgroundRevision{
		UnitAIP:              "192.168.9.101",
		UnitBIP:              "192.168.9.102",
		LegacyFusedObjectKey: value("legacy/fused.pcd"),
		CoordinateSchema:     LaserBackgroundSchemaLegacyFused,
	}
	if err := validateLaserBackgroundRevision(legacy); err != nil {
		t.Fatalf("合法 legacy revision 被拒绝: %v", err)
	}

	verifiedLegacy := LaserBackgroundRevision{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102",
		LegacyFusedObjectKey: value("legacy/fused.pcd"),
		LegacyFusedPoints:    300,
		LegacyFusedChecksum:  value("fused-checksum"),
		CompatibilitySite:    value("compat-site"),
		CompatibilityRegion:  value("compat-region"),
		CompatibilityEvidence: json.RawMessage(`{
            "binding_version":1,
            "pipeline_revision":"legacy_region_fused_v1",
            "source_revision_id":1,
            "source_scan_id":176,
            "source_fused_points":300,
            "source_fused_xyz_sha256":"fused-checksum",
            "reference_scans":[{"scan_id":197}],
            "audit_report_sha256":"audit-checksum",
            "harness_report_sha256":"harness-checksum"
        }`),
		UnitAIdentity:         json.RawMessage(`{"ip":"192.168.9.101"}`),
		UnitBIdentity:         json.RawMessage(`{"ip":"192.168.9.102"}`),
		UnitADeviceConfigHash: value("device-a"), UnitBDeviceConfigHash: value("device-b"),
		UnitAScanConfigHash: value("scan-a"), UnitBScanConfigHash: value("scan-b"),
		CoordinateSchema: LaserBackgroundSchemaLegacyVerifiedFused,
	}
	if err := validateLaserBackgroundRevision(verifiedLegacy); err != nil {
		t.Fatalf("合法已验证 legacy revision 被拒绝: %v", err)
	}
	verifiedLegacy.LegacyFusedChecksum = nil
	if err := validateLaserBackgroundRevision(verifiedLegacy); err == nil {
		t.Fatal("已验证 legacy 缺少融合 checksum 必须拒绝")
	}
}

func TestWithBackgroundRevisionID(t *testing.T) {
	raw, err := withBackgroundRevisionID(json.RawMessage(`{"measure":{"valid":false}}`), 42)
	if err != nil {
		t.Fatalf("写入 background_revision_id 失败: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("结果不是合法 JSON: %v", err)
	}
	if got["background_revision_id"] != float64(42) {
		t.Fatalf("background_revision_id 错误: %+v", got)
	}
	if _, ok := got["measure"]; !ok {
		t.Fatalf("原有统计字段被覆盖: %+v", got)
	}
	if _, err := withBackgroundRevisionID(json.RawMessage(`[]`), 1); err == nil {
		t.Fatal("非对象 stats 必须拒绝")
	}
}
