package laser

import (
	"math"
	"testing"

	"io.gomob/server/pkg/repo"
)

// 合成"房间"：四面墙 + 地面（法向各异，点到面可全约束 6DoF），A 系直出；
// B 云 = 真值 T_ab⁻¹ 映射回 B 系。精修应从带扰动初值恢复 T_ab。
func synthRoom() []float32 {
	var pts []float32
	step := float32(30)
	// 地面 z=0 (5m×5m)
	for x := float32(-2500); x <= 2500; x += step {
		for y := float32(-2500); y <= 2500; y += step {
			pts = append(pts, x, y, 0)
		}
	}
	// 两面 x 墙、两面 y 墙 (高 2m)
	for a := float32(-2500); a <= 2500; a += step {
		for z := float32(0); z <= 2000; z += step {
			pts = append(pts, -2500, a, z, 2500, a, z, a, -2500, z, a, 2500, z)
		}
	}
	return pts
}

func applyT16(xyz []float32, m [16]float32) []float32 {
	out := make([]float32, len(xyz))
	for i := 0; i+2 < len(xyz); i += 3 {
		x, y, z := xyz[i], xyz[i+1], xyz[i+2]
		out[i] = m[0]*x + m[1]*y + m[2]*z + m[3]
		out[i+1] = m[4]*x + m[5]*y + m[6]*z + m[7]
		out[i+2] = m[8]*x + m[9]*y + m[10]*z + m[11]
	}
	return out
}

func rotZ16(deg float32, tx, ty, tz float32) [16]float32 {
	r := float64(deg) * math.Pi / 180
	c, s := float32(math.Cos(r)), float32(math.Sin(r))
	return [16]float32{c, -s, 0, tx, s, c, 0, ty, 0, 0, 1, tz, 0, 0, 0, 1}
}

func invertRigid16(m [16]float32) [16]float32 {
	// 刚体逆: R^T, -R^T t
	var o [16]float32
	for r := 0; r < 3; r++ {
		for c := 0; c < 3; c++ {
			o[r*4+c] = m[c*4+r]
		}
	}
	o[3] = -(o[0]*m[3] + o[1]*m[7] + o[2]*m[11])
	o[7] = -(o[4]*m[3] + o[5]*m[7] + o[6]*m[11])
	o[11] = -(o[8]*m[3] + o[9]*m[7] + o[10]*m[11])
	o[15] = 1
	return o
}

// 精修应把带 60mm/2° 扰动的初值收敛回真值（对应真机场景：标记外参偏 ~60mm 被精修救回）。
func TestRefineBToARecoversPerturbedInit(t *testing.T) {
	room := synthRoom()
	truth := rotZ16(30, 800, -400, 50) // 真 B→A
	cloudB := applyT16(room, invertRigid16(truth))
	init := rotZ16(32, 860, -350, 90) // 扰动: +2° / Δt≈(60,50,40)

	got, st := RefineBToA(room, cloudB, init, DefaultRefineBToAParams())
	if !st.Applied {
		t.Fatalf("精修未采纳: %+v", st)
	}
	dT, dR := deltaSE3(mat16ToF64(truth), mat16ToF64(got))
	if dT > 5 || dR > 0.2 {
		t.Fatalf("精修未收敛到真值: 平移差 %.2fmm 旋转差 %.3f° (要求 ≤5mm/0.2°), stats=%+v", dT, dR, st)
	}
}

// 初值扰动超守卫上限（>150mm）→ 精修若把它拉回真值，delta 会超守卫 → 拒绝并沿用初值。
// （守卫语义：精修量过大 = 疑似发散/初值不可信，宁可沿用初值并告警，不做大幅隐式改写。）
func TestRefineBToAGuardRejectsHugeDelta(t *testing.T) {
	room := synthRoom()
	truth := rotZ16(30, 800, -400, 50)
	cloudB := applyT16(room, invertRigid16(truth))
	init := rotZ16(30, 1100, -400, 50) // 平移偏 300mm > 守卫 150mm

	got, st := RefineBToA(room, cloudB, init, DefaultRefineBToAParams())
	if st.Applied {
		t.Fatalf("超守卫应拒绝, got applied stats=%+v", st)
	}
	if got != init {
		t.Fatalf("拒绝时应原样返回初值")
	}
}

// 点云过稀 → 拒绝、返回初值。
func TestRefineBToATooSparse(t *testing.T) {
	tiny := []float32{0, 0, 0, 100, 0, 0, 0, 100, 0}
	init := rotZ16(0, 0, 0, 0)
	got, st := RefineBToA(tiny, tiny, init, DefaultRefineBToAParams())
	if st.Applied || got != init {
		t.Fatalf("过稀点云应拒绝并回初值, stats=%+v", st)
	}
}

func TestProductionRefineAcceptance(t *testing.T) {
	accepted := RefineBToAStats{
		Applied: true, Pairs: DefaultRefineBToAParams().MinPairs,
		RMSMM: 10, DeltaTransMM: 20, DeltaRotDeg: 0.5,
	}
	if ok, reason := productionRefineAcceptance(accepted); !ok || reason != "accepted" {
		t.Fatalf("合格精修被拒绝: ok=%v reason=%s", ok, reason)
	}
	if ok, _ := productionRefineAcceptance(RefineBToAStats{Reason: "重叠面对应不足"}); ok {
		t.Fatal("未应用精修不得进入生产测量")
	}
	tooFar := accepted
	tooFar.DeltaTransMM = maxProductionRefineDeltaTransMM + 0.1
	if ok, reason := productionRefineAcceptance(tooFar); ok || reason != "translation_delta_too_large" {
		t.Fatalf("大平移精修必须被拒绝: ok=%v reason=%s", ok, reason)
	}
	tooRotated := accepted
	tooRotated.DeltaRotDeg = maxProductionRefineDeltaRotDeg + 0.1
	if ok, reason := productionRefineAcceptance(tooRotated); ok || reason != "rotation_delta_too_large" {
		t.Fatalf("大旋转精修必须被拒绝: ok=%v reason=%s", ok, reason)
	}
	tooNoisy := accepted
	tooNoisy.RMSMM = maxProductionRefineRMSMM + 0.1
	if ok, reason := productionRefineAcceptance(tooNoisy); ok || reason != "rms_too_large" {
		t.Fatalf("高残差精修必须被拒绝: ok=%v reason=%s", ok, reason)
	}
}

func TestProductionRefineAcceptanceUsesVerifiedLegacyAlgorithmGuard(t *testing.T) {
	job197 := RefineBToAStats{
		Applied: true, Pairs: 1028, RMSMM: 11.305,
		DeltaTransMM: 61.943, DeltaRotDeg: 1.124,
	}
	if ok, _ := productionRefineAcceptance(job197); ok {
		t.Fatal("新 A/B 背景不得继承 legacy 的旧算法规则")
	}
	if ok, reason := productionRefineAcceptanceForBackground(job197, repo.LaserBackgroundSchemaLegacyVerifiedFused); !ok || reason != "accepted" {
		t.Fatalf("真实准确 job197 应通过已验证 legacy 规则: ok=%v reason=%s", ok, reason)
	}
	// job214 被旧 65mm/1.2°门误杀；同一融合云按修改前网页算法真实重放为
	// 1771.675×529.667×764.958mm，因此完整 legacy binding 下应进入旧算法测量。
	job214 := RefineBToAStats{
		Applied: true, Pairs: 1125, RMSMM: 10.900274,
		DeltaTransMM: 96.98354, DeltaRotDeg: 2.1186337,
	}
	if ok, reason := productionRefineAcceptance(job214); ok || reason != "translation_delta_too_large" {
		t.Fatalf("新 A/B schema 仍应拒绝 job214 大修正: ok=%v reason=%s", ok, reason)
	}
	if ok, reason := productionRefineAcceptanceForBackground(job214, repo.LaserBackgroundSchemaLegacyVerifiedFused); !ok || reason != "accepted" {
		t.Fatalf("真实准确 job214 应恢复旧网页算法: ok=%v reason=%s", ok, reason)
	}
	tooFar := job214
	tooFar.DeltaTransMM = DefaultRefineBToAParams().MaxDeltaTrans + 0.1
	if ok, reason := productionRefineAcceptanceForBackground(tooFar, repo.LaserBackgroundSchemaLegacyVerifiedFused); ok || reason != "translation_delta_too_large" {
		t.Fatalf("超出精修算法发散守卫必须拒绝: ok=%v reason=%s", ok, reason)
	}
	tooNoisy := job214
	tooNoisy.RMSMM = maxProductionRefineRMSMM + 0.1
	if ok, reason := productionRefineAcceptanceForBackground(tooNoisy, repo.LaserBackgroundSchemaLegacyVerifiedFused); ok || reason != "rms_too_large" {
		t.Fatalf("legacy 旧算法仍必须拒绝高残差精修: ok=%v reason=%s", ok, reason)
	}
}
