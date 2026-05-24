package api

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/repo"
)

type openAdHocGroupReq struct {
	MemberUserIDs []string `json:"member_user_ids"`
	Title         string   `json:"title,omitempty"`
}

// OpenAdHocGroup 通讯录多选发起多人通话时拿/建一个临时群会话。
//
// 设计：同一帮人（含发起人）再次发起仍是同一个 conversation —
// 用 subject_kind="ad_hoc_group" + subject_id = hash(sorted(uid+members)) 保证幂等。
// 这样通话期间发的图片、流水、文字都挂在同一个 conv，下次召集同一帮人时上下文连续，
// 不会刷一堆"昨天通话"碎片群。
//
// 复用 [ConversationRepo.GetOrCreateSubjectGroup] 现成的事务、partial unique index 兜底。
func (h *Handler) OpenAdHocGroup(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	if !h.enforcer.Allow(callerRole(r), "message", "send") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	var req openAdHocGroupReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	members, apiErr := parseAdHocMembers(uid, req.MemberUserIDs)
	if apiErr != nil {
		httpx.WriteError(w, apiErr)
		return
	}
	title := strings.TrimSpace(req.Title)
	if title == "" {
		title = "多人连线"
	}
	subjectID := adHocSubjectID(members)
	conv, err := h.conversations.GetOrCreateSubjectGroup(
		r.Context(),
		title,
		"ad_hoc_group",
		subjectID,
		members,
	)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	summary, err := h.conversations.FindForUser(r.Context(), uid, conv.ID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	h.recordAudit(r, "conversation.ad_hoc.open", "conversation:"+strconv.FormatInt(conv.ID, 10), nil, map[string]any{
		"members": members,
		"title":   title,
	})
	httpx.OK(w, toConversationDTO(summary))
}

func parseAdHocMembers(self int64, raw []string) ([]int64, *httpx.APIError) {
	if len(raw) == 0 {
		return nil, httpx.ErrBadParam
	}
	seen := map[int64]struct{}{self: {}}
	members := []int64{self}
	for _, s := range raw {
		id, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
		if err != nil || id <= 0 || id == self {
			continue
		}
		if _, ok := seen[id]; ok {
			continue
		}
		seen[id] = struct{}{}
		members = append(members, id)
	}
	if len(members) < 2 {
		return nil, httpx.ErrBadParam
	}
	return members, nil
}

// adHocSubjectID 把成员 ID 集合稳定哈希成 int64，保证 (a,b,c) 与 (c,b,a) 一致。
// 用 sha256 前 8 字节，最高位清零确保正数（避免 BIGINT 溢出符号位歧义）。
func adHocSubjectID(members []int64) int64 {
	sorted := append([]int64(nil), members...)
	sort.Slice(sorted, func(i, j int) bool { return sorted[i] < sorted[j] })
	buf := make([]byte, 8*len(sorted))
	for i, m := range sorted {
		binary.BigEndian.PutUint64(buf[i*8:], uint64(m))
	}
	sum := sha256.Sum256(buf)
	v := int64(binary.BigEndian.Uint64(sum[:8]) & 0x7fffffffffffffff)
	if v == 0 {
		v = 1
	}
	return v
}
