package api

import (
	"errors"
	"net/http"

	"io.gomob/server/internal/feedback"
	"io.gomob/server/pkg/httpx"
)

func (h *Handler) SubmitFeedback(w http.ResponseWriter, r *http.Request) {
	userID := callerUserID(r)
	if userID == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	req, err := feedback.DecodeHTTP(w, r)
	if err != nil {
		h.writeFeedbackError(w, err, userID)
		return
	}
	if req.UserAgent == "" {
		req.UserAgent = r.UserAgent()
	}
	resp, err := h.feedback.Submit(req, feedback.Submitter{
		UserID:     userID,
		RemoteAddr: r.RemoteAddr,
	})
	if err != nil {
		h.writeFeedbackError(w, err, userID)
		return
	}
	httpx.OK(w, resp)
}

func (h *Handler) writeFeedbackError(w http.ResponseWriter, err error, userID int64) {
	var ve feedback.ValidationError
	if errors.As(err, &ve) {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, ve.Message))
		return
	}
	h.log.Error("提交 App 问题反馈失败", "err", err, "user_id", userID)
	httpx.WriteError(w, httpx.ErrInternal)
}
