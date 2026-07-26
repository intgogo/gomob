package repo

import (
	"strings"
	"testing"
)

// scanLaserJob 的 row.Scan(...) 目标个数（手数：见 laser_scan.go scanLaserJob）。
// laserScanCols 列数必须与之严格相等，否则 Scan 错位读字段。改列时两处必须同步，本测试是守卫。
const laserScanScanArity = 24

func TestLaserScanColsArity(t *testing.T) {
	cols := strings.Split(laserScanCols, ",")
	for i := range cols {
		cols[i] = strings.TrimSpace(cols[i])
	}
	if len(cols) != laserScanScanArity {
		t.Fatalf("laserScanCols 列数 = %d，期望 %d（与 scanLaserJob 的 Scan 目标数一致）", len(cols), laserScanScanArity)
	}
	// 关键列必须在位（防重命名/错列）。
	want := []string{
		"id", "session_key", "inspection_id", "owner_user_id", "unit_a_ip", "unit_b_ip",
		"align", "align_method", "keep_ratio", "status", "pts_a", "pts_b", "fused", "after_crop",
		"fused_object_key", "unit_a_object_key", "unit_b_object_key", "measured_object_key", "calib_object_key",
		"b_to_a", "stats", "error_message", "created_at", "updated_at",
	}
	for i, w := range want {
		if cols[i] != w {
			t.Errorf("第 %d 列 = %q，期望 %q", i, cols[i], w)
		}
	}
}

func TestLaserScanTransitions(t *testing.T) {
	cases := []struct {
		from string
		to   string
		want bool
	}{
		{LaserScanStatusCapturing, LaserScanStatusFusing, true},
		{LaserScanStatusCapturing, LaserScanStatusFailed, true},
		{LaserScanStatusCapturing, LaserScanStatusCancelled, true},
		{LaserScanStatusFusing, LaserScanStatusDone, true},
		{LaserScanStatusFusing, LaserScanStatusFailed, true},
		{LaserScanStatusFusing, LaserScanStatusCancelled, true},

		{LaserScanStatusCapturing, LaserScanStatusDone, false},
		{LaserScanStatusFusing, LaserScanStatusFusing, false},
		{LaserScanStatusDone, LaserScanStatusFailed, false},
		{LaserScanStatusDone, LaserScanStatusCancelled, false},
		{LaserScanStatusFailed, LaserScanStatusDone, false},
		{LaserScanStatusFailed, LaserScanStatusCancelled, false},
		{LaserScanStatusCancelled, LaserScanStatusFusing, false},
		{LaserScanStatusCancelled, LaserScanStatusDone, false},
		{LaserScanStatusCancelled, LaserScanStatusFailed, false},
		{"unknown", LaserScanStatusFailed, false},
		{LaserScanStatusCapturing, "unknown", false},
	}

	for _, c := range cases {
		if got := isLaserScanTransitionAllowed(c.from, c.to); got != c.want {
			t.Errorf("isLaserScanTransitionAllowed(%q, %q) = %v，期望 %v", c.from, c.to, got, c.want)
		}
	}
}

func TestLaserScanTerminalStatesCannotBeOverwritten(t *testing.T) {
	for _, terminal := range []string{
		LaserScanStatusDone,
		LaserScanStatusFailed,
		LaserScanStatusCancelled,
	} {
		for _, target := range []string{
			LaserScanStatusFusing,
			LaserScanStatusDone,
			LaserScanStatusFailed,
			LaserScanStatusCancelled,
		} {
			if isLaserScanTransitionAllowed(terminal, target) {
				t.Errorf("终态 %q 不得迁移到 %q", terminal, target)
			}
		}
	}
}

func TestFindLatestLaserMeasurementSQLFiltersCanonicalVehicleResult(t *testing.T) {
	normalized := strings.Join(strings.Fields(findLatestLaserMeasurementsSQL), " ")
	wants := []string{
		"unit_a_ip=$1 AND unit_b_ip=$2",
		"status=$3",
		"measured_object_key IS NOT NULL",
		"length(btrim(measured_object_key)) > 0",
		"stats #>> '{measure,valid}' = 'true'",
		"stats #>> '{measured_artifact,xyz_sha256}'",
		"stats #>> '{measured_artifact,final_b_to_a_sha256}'",
		"stats #>> '{measured_artifact,coordinate_schema}' = 'unit_a_world_mm_v1'",
		"stats #>> '{measured_artifact,site_revision}'",
		"stats #>> '{measured_artifact,region_revision}'",
		"stats #>> '{measured_artifact,source_points}'",
		"($4::bigint IS NULL OR owner_user_id=$4)",
		"ORDER BY id DESC LIMIT $5",
	}
	for _, want := range wants {
		if !strings.Contains(normalized, want) {
			t.Errorf("最近有效车辆测量 SQL 缺少约束 %q\nSQL: %s", want, normalized)
		}
	}
}

func TestLaserScanStatusConstants(t *testing.T) {
	// migration 0018 的 CHECK 约束枚举必须与这些常量一一对应。
	all := []string{
		LaserScanStatusCapturing, LaserScanStatusFusing,
		LaserScanStatusDone, LaserScanStatusFailed, LaserScanStatusCancelled,
	}
	seen := map[string]bool{}
	for _, s := range all {
		if s == "" {
			t.Error("状态常量为空")
		}
		if seen[s] {
			t.Errorf("状态常量重复: %q", s)
		}
		seen[s] = true
	}
	for _, want := range []string{"capturing", "fusing", "done", "failed", "cancelled"} {
		if !seen[want] {
			t.Errorf("缺状态常量 %q（须与 migration 0018 CHECK 对齐）", want)
		}
	}
}
