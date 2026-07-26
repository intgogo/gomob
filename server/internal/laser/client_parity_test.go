package laser

import (
	"context"
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"testing"

	"io.gomob/server/pkg/repo"
)

// TestClientParity 同时守住两类分叉：
//  1. 网页旧请求携带几何、App 不携带几何时，服务端解析出的 site/region/background revision 相同；
//  2. 网页实时 done 事件与 App 断线后 REST status/latest 恢复出的完整测量结果相同。
func TestClientParity(t *testing.T) {
	h, _, _, _ := newTestHandler(t, true)
	points := [][3]float32{{-1200, -800, 0}, {1200, -800, 0}, {1200, 2800, 0}, {-1200, 2800, 0}}
	pointsJSON, _ := json.Marshal(points)
	if err := h.regionCalib.Upsert(context.Background(), repo.LaserRegionCalibration{
		UnitAIP: "192.168.9.101", UnitBIP: "192.168.9.102", Enabled: true,
		Points: pointsJSON, Source: "parity_test",
	}); err != nil {
		t.Fatal(err)
	}

	appFilter, appRegion, err := h.resolveRegionCalibration(
		context.Background(), "192.168.9.101", "192.168.9.102", "site", testSiteJSON, nil,
	)
	if err != nil {
		t.Fatalf("App 解析服务端区域失败: %v", err)
	}
	webFilter, webRegion, err := h.resolveRegionCalibration(
		context.Background(), "192.168.9.101", "192.168.9.102", "site", testSiteJSON,
		&PointRegionFilter{Enabled: true, Points: points, BToA: make([]float32, 16)},
	)
	if err != nil {
		t.Fatalf("网页旧请求一致性校验失败: %v", err)
	}
	if !reflect.DeepEqual(appFilter, webFilter) || appRegion.PointsSHA256 != webRegion.PointsSHA256 {
		t.Fatalf("App/网页生效区域不一致:\napp=%+v\nweb=%+v", appFilter, webFilter)
	}
	siteRevision, err := canonicalSiteSHA256(testSiteJSON)
	if err != nil {
		t.Fatal(err)
	}
	const backgroundRevision int64 = 301
	bToA := identity16()
	bToAJSON, _ := json.Marshal(bToA)
	artifact := MeasuredCloudArtifact{
		XYZSHA256:          strings.Repeat("a", 64),
		CoordinateSchema:   MeasuredCoordinateSchemaUnitAWorldMMV1,
		SourcePoints:       548996,
		SiteRevision:       siteRevision,
		RegionRevision:     appRegion.PointsSHA256,
		BackgroundRevision: backgroundRevision,
		FinalBToASHA256:    cloudFloatSHA256(bToA[:]),
	}

	dims := Dimensions{LengthMM: 1768, WidthMM: 531, HeightMM: 763, BodyPts: 548996, Valid: true}
	axle := AxleResult{
		NumAxles: 4, WheelbasesMM: []float32{355, 360, 345}, TotalWheelbaseMM: 1060,
		FrontOverhangMM: 352, RearOverhangMM: 356, Valid: true,
	}
	cargo := CargoBox{
		HasBox: true, OuterLengthMM: 1060, OuterWidthMM: 455, DepthMM: 499,
		InnerWidthMM: 421, Valid: true,
	}
	ground := GroundPlane{NX: 0.01, NY: -0.02, NZ: 0.9997, D: -123, Valid: true}
	siteRMS := 3.2
	siteMarkers := 6
	overlay := VehicleOverlay{
		Valid: true,
		VehicleBox: [][3]float32{{0, 0, 0}, {1768, 0, 0}, {1768, 531, 0}, {0, 531, 0},
			{0, 0, 763}, {1768, 0, 763}, {1768, 531, 763}, {0, 531, 763}},
		HasCargoBox: true,
		CargoBox: [][3]float32{
			{600, 30, 260}, {1660, 30, 260}, {1660, 485, 260}, {600, 485, 260},
			{600, 30, 759}, {1660, 30, 759}, {1660, 485, 759}, {600, 485, 759},
		},
		AxleLines: [][2][3]float32{
			{{352, 0, 20}, {352, 531, 20}}, {{707, 0, 20}, {707, 531, 20}},
			{{1067, 0, 20}, {1067, 531, 20}}, {{1412, 0, 20}, {1412, 531, 20}},
		},
	}
	stats := mustJSON(map[string]any{
		"measure": dims, "axle": axle, "cargo_box": cargo, "overlay": overlay,
		"ground":       ground,
		"measure_mode": "bg_subtract", "compliance": Compliance{Reason: "vehicle_type_missing"},
		"bg_set": true, "background_compatible": true, "background_reason": "ready",
		"background_revision_id": backgroundRevision, "background_schema": repo.LaserBackgroundSchemaRawUnitFramesV1,
		"fg_points": 548996, "measured_points": 548996,
		"measured_artifact": artifact,
		"site_calibration": SiteCalibrationSnapshot{
			MatrixSHA256: siteRevision, RMSErrorMM: &siteRMS, CommonMarkers: &siteMarkers,
		},
		"region_calibration": RegionCalibrationSnapshot{Set: true, Enabled: true, PointsSHA256: appRegion.PointsSHA256},
	})
	align := "site"
	fused := 2049840
	ptsA := 1024510
	ptsB := 1025330
	fusedKey := "laser-scans/session-207/fused.pcd"
	unitAKey := "laser-scans/session-207/unit_a.pcd"
	unitBKey := "laser-scans/session-207/unit_b.pcd"
	measuredKey := "laser-scans/session-207/measured.pcd"
	restJob := &repo.LaserScanJob{
		ID: 207, SessionKey: "session-207", Status: repo.LaserScanStatusDone,
		Align: "site", AlignMethod: &align, Fused: &fused, PtsA: &ptsA, PtsB: &ptsB,
		FusedObjectKey: &fusedKey, UnitAObjectKey: &unitAKey, UnitBObjectKey: &unitBKey,
		MeasuredObjectKey: &measuredKey,
		Stats:             stats,
		BToA:              bToAJSON,
	}
	serverPayload := jobView(restJob)
	appResult := parityResultSubset(serverPayload)

	webEvent := FusionDoneEvent{
		Kind: "laser", JobID: 207, SessionKey: "session-207",
		ResultObjectKey: fusedKey, UnitAObjectKey: unitAKey, UnitBObjectKey: unitBKey,
		MeasuredObjectKey: measuredKey,
		MeasuredArtifact:  &artifact,
		Points:            fused, PtsA: ptsA, PtsB: ptsB, AlignMethod: "site",
		SiteRevision: siteRevision, RegionRevision: appRegion.PointsSHA256,
		SiteQualityVerified: true, ProductionEligible: true,
		LengthMM: dims.LengthMM, WidthMM: dims.WidthMM, HeightMM: dims.HeightMM, MeasureValid: true,
		ComplianceReason: "vehicle_type_missing", MeasMode: "bg_subtract",
		BackgroundSet: true, BackgroundCompatible: true, BackgroundIncompatible: false,
		BackgroundReason: "ready", BackgroundRevisionID: backgroundRevision,
		BackgroundSchema: repo.LaserBackgroundSchemaRawUnitFramesV1,
		FgPoints:         548996, MeasuredPoints: 548996,
		NumAxles: axle.NumAxles, WheelbasesMM: axle.WheelbasesMM, TotalWheelbaseMM: axle.TotalWheelbaseMM,
		FrontOverhangMM: axle.FrontOverhangMM, RearOverhangMM: axle.RearOverhangMM, AxleValid: true,
		HasCargoBox: true, BoxOuterLengthMM: cargo.OuterLengthMM, BoxOuterWidthMM: cargo.OuterWidthMM,
		BoxDepthMM: cargo.DepthMM, BoxInnerWidthMM: cargo.InnerWidthMM, Overlay: &overlay,
		GroundNX: ground.NX, GroundNY: ground.NY, GroundNZ: ground.NZ, GroundD: ground.D, GroundValid: true,
	}
	webResult := parityResultSubset(structToMap(t, webEvent))
	if !reflect.DeepEqual(webResult, appResult) {
		webJSON, _ := json.Marshal(webResult)
		appJSON, _ := json.Marshal(appResult)
		t.Fatalf("网页 WS 与 App REST 结果不一致:\nweb=%s\napp=%s", webJSON, appJSON)
	}

	raw, _ := json.Marshal(serverPayload)
	fmt.Printf("SERVER_SCAN_PAYLOAD: %s\n", raw)
}

func structToMap(t *testing.T, value any) map[string]any {
	t.Helper()
	raw, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err := json.Unmarshal(raw, &result); err != nil {
		t.Fatal(err)
	}
	return result
}

func parityResultSubset(value map[string]any) map[string]any {
	// 先经 JSON 归一化数值类型，避免 float32/float64 造成伪差异。
	raw, _ := json.Marshal(value)
	var normalized map[string]any
	_ = json.Unmarshal(raw, &normalized)
	keys := []string{
		"session_key", "result_object_key", "unit_a_object_key", "unit_b_object_key", "measured_object_key",
		"points", "pts_a", "pts_b", "align_method",
		"site_revision", "region_revision", "site_quality_verified", "site_quality_override",
		"site_quality_override_reason", "production_eligible", "meas_mode", "measure_valid",
		"measure_reason", "background_captured",
		"length_mm", "width_mm", "height_mm", "compliance_determined", "compliance_reason", "compliant", "violations",
		"background_set", "background_compatible", "background_incompatible", "background_reason",
		"background_revision_id", "background_schema", "fg_points", "measured_points",
		"measured_artifact",
		"axle_valid", "num_axles", "wheelbases_mm", "total_wheelbase_mm",
		"front_overhang_mm", "rear_overhang_mm", "has_cargo_box",
		"box_outer_length_mm", "box_outer_width_mm", "box_depth_mm", "box_inner_width_mm", "overlay",
		"ground_nx", "ground_ny", "ground_nz", "ground_d", "ground_valid",
	}
	result := make(map[string]any, len(keys))
	for _, key := range keys {
		if item, ok := normalized[key]; ok {
			result[key] = item
		}
	}
	return result
}
