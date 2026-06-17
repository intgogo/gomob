package laser

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestZZSerOverlay(t *testing.T) {
	a := loadVendorPCD(t, vendorSession+"/1.pcd")
	b := loadVendorPCD(t, vendorSession+"/2.pcd")
	fused := append(append([]float32{}, a...), b...)
	ov := BuildVehicleOverlay(fused, DefaultMeasureParams(), DefaultAxleParams(), DefaultCargoBoxParams())
	evt := FusionDoneEvent{Kind: "laser", Overlay: overlayPtr(ov)}
	js, _ := json.Marshal(evt)
	for _, k := range []string{`"overlay"`, `"vehicle_box"`, `"cargo_box"`, `"axle_lines"`, `"has_cargo_box"`} {
		if !strings.Contains(string(js), k) {
			t.Errorf("缺字段 %s", k)
		}
	}
	t.Logf("overlay JSON 片段: vehicle_box 角点数=%d cargo=%v 轴线数=%d", len(ov.VehicleBox), ov.HasCargoBox, len(ov.AxleLines))
}
