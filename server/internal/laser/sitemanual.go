package laser

import (
	"encoding/json"
	"math"
	"net/http"
	"strconv"
	"strings"
)

const manualSiteFramingMessage = "手动 RGB 点对输入已收到，但当前数据只有二维像素射线，缺少已知尺度靶点角点或同一点多航向三角化约束，不能解出米制 B→A 外参。请先使用自动 ArUco 取景标定；手动求解需补齐真实几何约束后才能保存。"

type manualSiteFramingRequest struct {
	UnitAIP string                  `json:"unit_a_ip"`
	UnitBIP string                  `json:"unit_b_ip"`
	Pairs   []manualSiteFramingPair `json:"pairs"`
}

type manualSiteFramingPair struct {
	Label string                    `json:"label"`
	A     manualSiteFramingPointObs `json:"a"`
	B     manualSiteFramingPointObs `json:"b"`
}

type manualSiteFramingPointObs struct {
	Role      string  `json:"role"`
	ShotIndex int     `json:"shotIndex"`
	Heading   float64 `json:"heading"`
	X         float64 `json:"x"`
	Y         float64 `json:"y"`
	U         float64 `json:"u"`
	V         float64 `json:"v"`
	W         float64 `json:"w"`
	H         float64 `json:"h"`
}

type manualSiteFramingResp struct {
	OK           bool     `json:"ok"`
	NCommon      int      `json:"n_common"`
	RMSM         float64  `json:"rms_m"`
	Message      string   `json:"message"`
	Log          string   `json:"log,omitempty"`
	Requirements []string `json:"requirements,omitempty"`
}

// SiteFramingManual POST /v1/scans/laser/site-framing/manual
//
// 当前前端提交的是 A/B 各一帧上的同名 2D 像素点。它只能定义两组相机射线，不能给出米制深度；
// 因此这里先接正式端点并诚实拒绝欠约束输入，避免把错误外参写成 manual_rgb。
func (h *Handler) SiteFramingManual(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeManualSiteFramingResp(w, http.StatusUnauthorized, 0, "需要登录")
		return
	}
	if !isAdmin(r) {
		writeManualSiteFramingResp(w, http.StatusForbidden, 0, "手动工位标定需 admin 角色")
		return
	}

	var req manualSiteFramingRequest
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	if err := dec.Decode(&req); err != nil {
		writeManualSiteFramingResp(w, http.StatusBadRequest, 0, "请求 JSON 无效: "+err.Error())
		return
	}

	ipA, ipB, status, message := h.managedStationUnitIPs(req.UnitAIP, req.UnitBIP)
	if status != 0 {
		writeManualSiteFramingResp(w, status, 0, message)
		return
	}
	req.UnitAIP, req.UnitBIP = ipA, ipB

	nCommon, errMsg := validateManualSiteFramingPairs(req.Pairs)
	if errMsg != "" {
		writeManualSiteFramingResp(w, http.StatusBadRequest, nCommon, errMsg)
		return
	}

	msg := manualSiteFramingUnderconstrainedMessage(req.Pairs)
	h.recordAudit(r, "laser.site_framing_manual", "bay:"+req.UnitAIP+"|"+req.UnitBIP, map[string]any{
		"unit_a_ip": req.UnitAIP, "unit_b_ip": req.UnitBIP,
		"n_common": nCommon, "accepted": false, "reason": "underconstrained",
	})
	writeJSON(w, http.StatusOK, manualSiteFramingResp{
		OK:      false,
		NCommon: nCommon,
		RMSM:    0,
		Message: msg,
		Log:     msg,
		Requirements: []string{
			"已知尺寸 ArUco/AprilTag/棋盘格角点，并把角点像素转成相机系 3D 观测",
			"或同一物理点在同一单元多个航向下出现，用云台几何先三角化成米制 3D 点",
			"生成 A/B 两侧 3D 对应点后再进入 solveSiteExtrinsic",
		},
	})
}

func manualSiteFramingUnderconstrainedMessage(pairs []manualSiteFramingPair) string {
	shotsA := map[int]struct{}{}
	shotsB := map[int]struct{}{}
	for _, pair := range pairs {
		shotsA[pair.A.ShotIndex] = struct{}{}
		shotsB[pair.B.ShotIndex] = struct{}{}
	}
	if len(shotsA) == 1 && len(shotsB) == 1 {
		return "已解析 " + strconv.Itoa(len(pairs)) +
			" 组手动 RGB 对应点，但这些点来自 A/B 各一张图片。单对图片的 2D 点对只能约束相对旋转和平移方向，缺少米制平移尺度，不能保存为点云融合用的 B→A 外参。请改用自动 ArUco，或标注已知尺寸靶点角点。"
	}
	return "已解析 " + strconv.Itoa(len(pairs)) +
		" 组手动 RGB 对应点，且包含多帧/多航向观测；但后端多航向三角化到米制 3D 点的求解尚未接入，当前不能保存为 B→A 外参。"
}

func validateManualSiteFramingPairs(pairs []manualSiteFramingPair) (int, string) {
	if len(pairs) < 4 {
		return len(pairs), "手动 RGB 点对至少需要 4 组，并且点位要尽量分散"
	}
	for i, pair := range pairs {
		if err := validateManualSiteFramingObs(pair.A, "A"); err != "" {
			return i, "第 " + pairNumber(i) + " 组 A 点无效: " + err
		}
		if err := validateManualSiteFramingObs(pair.B, "B"); err != "" {
			return i, "第 " + pairNumber(i) + " 组 B 点无效: " + err
		}
	}
	return len(pairs), ""
}

func validateManualSiteFramingObs(obs manualSiteFramingPointObs, role string) string {
	if obs.Role != "" && !strings.EqualFold(obs.Role, role) {
		return "role 应为 " + role
	}
	if obs.ShotIndex < 0 {
		return "shotIndex 不能为负"
	}
	if !finite(obs.Heading) || !finite(obs.X) || !finite(obs.Y) || !finite(obs.U) || !finite(obs.V) || !finite(obs.W) || !finite(obs.H) {
		return "坐标包含非有限数"
	}
	if obs.W <= 0 || obs.H <= 0 {
		return "图像尺寸必须大于 0"
	}
	if obs.X < 0 || obs.X > obs.W || obs.Y < 0 || obs.Y > obs.H {
		return "像素坐标超出图像范围"
	}
	if obs.U < 0 || obs.U > 1 || obs.V < 0 || obs.V > 1 {
		return "归一化坐标必须在 0..1"
	}
	return ""
}

func writeManualSiteFramingResp(w http.ResponseWriter, code, nCommon int, msg string) {
	writeJSON(w, code, manualSiteFramingResp{
		OK:      false,
		NCommon: nCommon,
		RMSM:    0,
		Message: msg,
		Log:     msg,
	})
}

func pairNumber(i int) string {
	return strconv.Itoa(i + 1)
}

func finite(v float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0)
}
