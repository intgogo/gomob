package laser

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strings"
	"time"

	"io.gomob/server/pkg/repo"
)

// finalizeSolvedSiteCalibration 把服务端解算成功的 B→A 直接写入工位真理源。
// 浏览器只能确认这个版本，不再承担“解算成功但没有入库”的单点职责。
func (h *Handler) finalizeSolvedSiteCalibration(
	r *http.Request,
	ipA, ipB, action string,
	result map[string]any,
) error {
	ok, _ := result["ok"].(bool)
	if !ok {
		h.recordAudit(r, action, "bay:"+ipA+"|"+ipB, solvedSiteAuditPayload(result, false, ""))
		return nil
	}
	if h.siteCalib == nil {
		err := errors.New("工位外参存储未配置")
		h.recordAudit(r, action, "bay:"+ipA+"|"+ipB, solvedSiteAuditPayload(result, false, err.Error()))
		return err
	}

	siteJSON, rmsMM, commonMarkers, err := solvedSiteCalibrationValues(result)
	if err != nil {
		h.recordAudit(r, action, "bay:"+ipA+"|"+ipB, solvedSiteAuditPayload(result, false, err.Error()))
		return err
	}
	revision, err := canonicalSiteSHA256(string(siteJSON))
	if err != nil {
		return fmt.Errorf("计算工位外参版本失败: %w", err)
	}
	uid := callerUserID(r)
	cal := repo.LaserSiteCalibration{
		UnitAIP:       ipA,
		UnitBIP:       ipB,
		SiteJSON:      siteJSON,
		Source:        "aruco",
		RMSErrorMM:    &rmsMM,
		CommonMarkers: &commonMarkers,
		UpdatedBy:     &uid,
	}
	ctx, cancel := context.WithTimeout(context.WithoutCancel(r.Context()), 5*time.Second)
	defer cancel()
	if err := h.siteCalib.Upsert(ctx, cal); err != nil {
		err = fmt.Errorf("保存工位外参失败: %w", err)
		h.recordAudit(r, action, "bay:"+ipA+"|"+ipB, solvedSiteAuditPayload(result, false, err.Error()))
		return err
	}
	result["server_persisted"] = true
	result["source"] = "aruco"
	result["site_json"] = siteJSON
	result["rms_error_mm"] = rmsMM
	result["common_markers"] = commonMarkers
	result["updated_by"] = uid
	result["revision"] = revision
	h.recordAudit(r, action, "bay:"+ipA+"|"+ipB, map[string]any{
		"ok": true, "server_persisted": true,
		"unit_a_ip": ipA, "unit_b_ip": ipB,
		"source": "aruco", "rms_error_mm": rmsMM,
		"common_markers": commonMarkers, "revision": revision,
	})
	return nil
}

func solvedSiteCalibrationValues(result map[string]any) (json.RawMessage, float64, int, error) {
	matrix, ok := result["b_to_a"]
	if !ok {
		matrix = result["matrix"]
	}
	siteJSON, err := json.Marshal(map[string]any{"b_to_a": matrix})
	if err != nil {
		return nil, 0, 0, fmt.Errorf("编码解算外参失败: %w", err)
	}
	if err := validateSiteExtrinsicJSON(string(siteJSON)); err != nil {
		return nil, 0, 0, fmt.Errorf("解算外参无效: %w", err)
	}
	rmsM, ok := finiteResultNumber(result["rms_m"])
	if !ok || rmsM < 0 {
		return nil, 0, 0, errors.New("解算结果缺少有效 rms_m")
	}
	commonValue, ok := finiteResultNumber(result["n_common"])
	if !ok || commonValue < 0 || math.Trunc(commonValue) != commonValue || commonValue > math.MaxInt {
		return nil, 0, 0, errors.New("解算结果缺少有效 n_common")
	}
	rmsMM := rmsM * 1000
	commonMarkers := int(commonValue)
	if err := validateProductionSiteQuality(&rmsMM, &commonMarkers); err != nil {
		return nil, 0, 0, fmt.Errorf("工位外参质量未达生产要求: %w", err)
	}
	return json.RawMessage(siteJSON), rmsMM, commonMarkers, nil
}

func finiteResultNumber(value any) (float64, bool) {
	var number float64
	switch v := value.(type) {
	case float64:
		number = v
	case float32:
		number = float64(v)
	case int:
		number = float64(v)
	case int64:
		number = float64(v)
	case json.Number:
		parsed, err := v.Float64()
		if err != nil {
			return 0, false
		}
		number = parsed
	default:
		return 0, false
	}
	return number, !math.IsNaN(number) && !math.IsInf(number, 0)
}

func solvedSiteAuditPayload(result map[string]any, persisted bool, errMessage string) map[string]any {
	payload := map[string]any{
		"ok":               result["ok"],
		"server_persisted": persisted,
		"rms_m":            result["rms_m"],
		"n_common":         result["n_common"],
	}
	if strings.TrimSpace(errMessage) != "" {
		payload["error"] = errMessage
	}
	return payload
}

func sameSiteCalibrationConfirmation(
	stored *repo.LaserSiteCalibration,
	request siteCalibrationPutReq,
	source string,
) bool {
	if stored == nil || !sameSiteMatrixWithin(string(stored.SiteJSON), string(request.SiteJSON), 1e-9) || stored.Source != source {
		return false
	}
	return sameOptionalFloat(stored.MeanErrorMM, request.MeanErrorMM) &&
		sameOptionalFloat(stored.MaxErrorMM, request.MaxErrorMM) &&
		sameOptionalFloat(stored.RMSErrorMM, request.RMSErrorMM) &&
		sameOptionalInt(stored.CommonMarkers, request.CommonMarkers)
}

func sameSiteMatrixWithin(a, b string, tolerance float64) bool {
	ma, errA := parseSiteMatrix(a)
	mb, errB := parseSiteMatrix(b)
	if errA != nil || errB != nil {
		return false
	}
	for i := range ma {
		if math.Abs(ma[i]-mb[i]) > tolerance {
			return false
		}
	}
	return true
}

func sameOptionalFloat(a, b *float64) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return math.Abs(*a-*b) <= 1e-6
}

func sameOptionalInt(a, b *int) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}
