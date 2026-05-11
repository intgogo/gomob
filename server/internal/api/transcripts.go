package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"strconv"
	"strings"

	"io.gomob/server/internal/asr"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/repo"
)

func defaultTranscriptConfig() repo.TranscriptConfig {
	engine := os.Getenv("GOMOB_ASR_ENGINE")
	if engine == "" {
		engine = "fireredasr2"
	}
	model := os.Getenv("GOMOB_ASR_MODEL")
	if model == "" {
		model = "FireRedASR2-AED"
	}
	language := os.Getenv("GOMOB_ASR_LANGUAGE")
	if language == "" {
		language = "zh"
	}
	return repo.TranscriptConfig{
		Engine:   engine,
		Model:    model,
		Language: language,
	}
}

type transcribeDraftVoiceReq struct {
	AssetID  flexibleJSONID `json:"asset_id"`
	Language string         `json:"language,omitempty"`
}

type transcribeDraftVoiceDTO struct {
	Text           string          `json:"text"`
	NormalizedText string          `json:"normalized_text"`
	Segments       json.RawMessage `json:"segments"`
	Confidence     *float32        `json:"confidence,omitempty"`
	Engine         string          `json:"engine"`
	Model          string          `json:"model"`
	Language       string          `json:"language"`
}

func (h *Handler) TranscribeDraftVoice(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	var req transcribeDraftVoiceReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	assetID, err := strconv.ParseInt(req.AssetID.String(), 10, 64)
	if err != nil || assetID <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	asset, err := h.assets.FindAssetByID(r.Context(), assetID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if asset.Kind != "message_voice" {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "只有消息语音资产支持转文字"))
		return
	}
	owned, err := h.assets.IsAssetOwnedByUser(r.Context(), assetID, uid)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if !owned {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	trCfg := defaultTranscriptConfig()
	if strings.TrimSpace(req.Language) != "" {
		trCfg.Language = strings.TrimSpace(req.Language)
	}
	client, err := asr.NewServiceClient(asr.Config{
		ServiceURL:     strings.TrimSpace(os.Getenv("GOMOB_ASR_URL")),
		Engine:         trCfg.Engine,
		Model:          trCfg.Model,
		Language:       trCfg.Language,
		MinIOEndpoint:  envOrDefault("GOMOB_MINIO_ENDPOINT", "127.0.0.1:9000"),
		MinIOAccessKey: envOrDefault("GOMOB_MINIO_ACCESS_KEY", "gomob"),
		MinIOSecretKey: envOrDefault("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"),
		MinIOUseSSL:    os.Getenv("GOMOB_MINIO_USE_SSL") == "true",
		Bucket:         envOrDefault("GOMOB_MINIO_BUCKET", "gomob-assets"),
		MaxAudioBytes:  envInt64OrDefault("GOMOB_ASR_MAX_AUDIO_BYTES", 50*1024*1024),
	})
	if err != nil {
		httpx.WriteError(w, httpx.NewError(40501, http.StatusServiceUnavailable, "语音转文字服务未配置："+err.Error()))
		return
	}
	result, err := client.TranscribeAsset(r.Context(), asset)
	if err != nil {
		if h.log != nil {
			h.log.Warn("草稿语音转写失败", "err", err, "asset_id", assetID, "user_id", uid)
		}
		httpx.WriteError(w, httpx.NewError(40503, http.StatusBadGateway, "语音转文字失败："+err.Error()))
		return
	}
	httpx.OK(w, transcribeDraftVoiceDTO{
		Text:           result.Text,
		NormalizedText: result.NormalizedText,
		Segments:       result.Segments,
		Confidence:     result.Confidence,
		Engine:         result.Engine,
		Model:          result.Model,
		Language:       result.Language,
	})
}

func (h *Handler) RetryMessageTranscript(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	messageID, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var conversationID int64
	var kind string
	err = h.pool.QueryRow(r.Context(),
		`SELECT conversation_id, kind FROM messages WHERE id=$1`,
		messageID,
	).Scan(&conversationID, &kind)
	if err != nil {
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	}
	if kind != "voice" {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "只有语音消息支持转写重试"))
		return
	}
	ok, err := h.conversations.IsMember(r.Context(), conversationID, uid)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if !ok {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	msg, err := h.transcripts.Retry(r.Context(), messageID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			voice, findErr := h.messages.FindByID(r.Context(), messageID)
			if findErr != nil {
				httpx.WriteError(w, httpx.ErrNotFound)
				return
			}
			tr, _, ensureErr := h.transcripts.EnsureForVoiceMessage(r.Context(), voice)
			if ensureErr != nil {
				httpx.WriteError(w, httpx.ErrInternal)
				return
			}
			if tr == nil {
				httpx.WriteError(w, httpx.ErrNotFound)
				return
			}
			msg = voice
		} else {
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
	}
	h.notifyTranscriptUpdate(r.Context(), msg)
	httpx.OK(w, toMessageDTO(msg))
}

func (h *Handler) notifyTranscriptUpdate(ctx context.Context, msg *repo.Message) {
	notifier, ok := h.realtime.(RealtimeTranscriptNotifier)
	if !ok || msg == nil {
		return
	}
	delivered, err := notifier.NotifyTranscriptUpdate(ctx, msg)
	if err != nil {
		if h.log != nil {
			h.log.Warn("语音转写实时推送失败",
				"err", err,
				"conversation_id", msg.ConversationID,
				"message_id", msg.ID,
			)
		}
		return
	}
	if h.log != nil {
		h.log.Debug("语音转写已实时推送",
			"conversation_id", msg.ConversationID,
			"message_id", msg.ID,
			"delivered_connections", delivered,
		)
	}
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt64OrDefault(key string, def int64) int64 {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return def
	}
	v, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || v <= 0 {
		return def
	}
	return v
}
