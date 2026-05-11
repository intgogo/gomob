package repo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	TranscriptStatusPending    = "pending"
	TranscriptStatusProcessing = "processing"
	TranscriptStatusDone       = "done"
	TranscriptStatusFailed     = "failed"
)

type TranscriptConfig struct {
	Engine   string
	Model    string
	Language string
}

func (c TranscriptConfig) normalized() TranscriptConfig {
	if strings.TrimSpace(c.Engine) == "" {
		c.Engine = "fireredasr2"
	}
	if strings.TrimSpace(c.Model) == "" {
		c.Model = "FireRedASR2-AED"
	}
	if strings.TrimSpace(c.Language) == "" {
		c.Language = "zh"
	}
	return c
}

type MessageTranscript struct {
	ID             int64
	MessageID      int64
	ConversationID int64
	AssetID        int64
	Status         string
	Engine         string
	Model          string
	Language       string
	Text           *string
	NormalizedText *string
	Segments       json.RawMessage
	Confidence     *float32
	ErrorMessage   *string
	AttemptCount   int
	NextRetryAt    time.Time
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

type TranscriptCompletion struct {
	Text           string
	NormalizedText string
	Segments       json.RawMessage
	Confidence     *float32
	Engine         string
	Model          string
	Language       string
}

type TranscriptRepo struct {
	pool *pgxpool.Pool
	cfg  TranscriptConfig
}

func NewTranscriptRepo(pool *pgxpool.Pool, cfg TranscriptConfig) *TranscriptRepo {
	return &TranscriptRepo{pool: pool, cfg: cfg.normalized()}
}

func (r *TranscriptRepo) EnsureForVoiceMessage(ctx context.Context, m *Message) (*MessageTranscript, bool, error) {
	if m == nil || m.Kind != "voice" || m.ID <= 0 {
		return nil, false, nil
	}
	assetID, ok := voiceAssetID(m.Payload)
	if !ok {
		return nil, false, nil
	}
	cfg := r.cfg.normalized()
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, false, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	tr := &MessageTranscript{}
	inserted := true
	err = scanTranscript(tx.QueryRow(ctx, `
		INSERT INTO message_transcripts(message_id, conversation_id, asset_id, status, engine, model, language)
		VALUES($1, $2, $3, 'pending', $4, $5, $6)
		ON CONFLICT (message_id) DO NOTHING
		RETURNING id, message_id, conversation_id, asset_id, status, engine, model, language,
		          text, normalized_text, segments, confidence, error_message,
		          attempt_count, next_retry_at, created_at, updated_at`,
		m.ID, m.ConversationID, assetID, cfg.Engine, cfg.Model, cfg.Language,
	), tr)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			inserted = false
			tr, err = r.FindByMessageID(ctx, m.ID)
			if err != nil {
				return nil, false, err
			}
		} else {
			return nil, false, err
		}
	}
	payload, err := updateMessageTranscriptPayload(ctx, tx, m.ID, transcriptPayloadPatch(tr))
	if err != nil {
		return nil, false, err
	}
	m.Payload = payload
	if err := tx.Commit(ctx); err != nil {
		return nil, false, err
	}
	return tr, inserted, nil
}

func (r *TranscriptRepo) FindByMessageID(ctx context.Context, messageID int64) (*MessageTranscript, error) {
	tr := &MessageTranscript{}
	err := scanTranscript(r.pool.QueryRow(ctx, `
		SELECT id, message_id, conversation_id, asset_id, status, engine, model, language,
		       text, normalized_text, segments, confidence, error_message,
		       attempt_count, next_retry_at, created_at, updated_at
		FROM message_transcripts
		WHERE message_id=$1`, messageID), tr)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return tr, nil
}

func (r *TranscriptRepo) ClaimNext(ctx context.Context, maxAttempts int) (*MessageTranscript, error) {
	if maxAttempts <= 0 {
		maxAttempts = 3
	}
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	tr := &MessageTranscript{}
	err = scanTranscript(tx.QueryRow(ctx, `
		WITH next AS (
			SELECT id
			FROM message_transcripts
			WHERE status IN ('pending', 'failed')
			  AND next_retry_at <= now()
			  AND attempt_count < $1
			ORDER BY created_at ASC, id ASC
			LIMIT 1
			FOR UPDATE SKIP LOCKED
		)
		UPDATE message_transcripts mt
		SET status='processing',
		    attempt_count=mt.attempt_count + 1,
		    updated_at=now()
		FROM next
		WHERE mt.id=next.id
		RETURNING mt.id, mt.message_id, mt.conversation_id, mt.asset_id, mt.status, mt.engine, mt.model, mt.language,
		          mt.text, mt.normalized_text, mt.segments, mt.confidence, mt.error_message,
		          mt.attempt_count, mt.next_retry_at, mt.created_at, mt.updated_at`, maxAttempts), tr)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return tr, nil
}

func (r *TranscriptRepo) Complete(ctx context.Context, id int64, completion TranscriptCompletion) (*Message, error) {
	if len(completion.Segments) == 0 {
		completion.Segments = json.RawMessage(`[]`)
	}
	if strings.TrimSpace(completion.NormalizedText) == "" {
		completion.NormalizedText = completion.Text
	}
	cfg := r.cfg.normalized()
	if strings.TrimSpace(completion.Engine) == "" {
		completion.Engine = cfg.Engine
	}
	if strings.TrimSpace(completion.Model) == "" {
		completion.Model = cfg.Model
	}
	if strings.TrimSpace(completion.Language) == "" {
		completion.Language = cfg.Language
	}
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	tr := &MessageTranscript{}
	err = scanTranscript(tx.QueryRow(ctx, `
		UPDATE message_transcripts
		SET status='done',
		    text=$2,
		    normalized_text=$3,
		    segments=$4,
		    confidence=$5,
		    error_message=NULL,
		    engine=$6,
		    model=$7,
		    language=$8,
		    updated_at=now()
		WHERE id=$1
		RETURNING id, message_id, conversation_id, asset_id, status, engine, model, language,
		          text, normalized_text, segments, confidence, error_message,
		          attempt_count, next_retry_at, created_at, updated_at`,
		id, completion.Text, completion.NormalizedText, completion.Segments,
		completion.Confidence, completion.Engine, completion.Model, completion.Language,
	), tr)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	msg, err := updateMessagePayloadFromTranscript(ctx, tx, tr)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return msg, nil
}

func (r *TranscriptRepo) Fail(ctx context.Context, id int64, message string, retryAfter time.Duration, maxAttempts int) (*Message, error) {
	if retryAfter <= 0 {
		retryAfter = time.Minute
	}
	if maxAttempts <= 0 {
		maxAttempts = 3
	}
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	tr := &MessageTranscript{}
	retrySeconds := int64(retryAfter.Seconds())
	if retrySeconds < 1 {
		retrySeconds = 1
	}
	err = scanTranscript(tx.QueryRow(ctx, `
		UPDATE message_transcripts
		SET status='failed',
		    error_message=$2,
		    next_retry_at=CASE WHEN attempt_count < $4 THEN now() + ($3 * interval '1 second') ELSE next_retry_at END,
		    updated_at=now()
		WHERE id=$1
		RETURNING id, message_id, conversation_id, asset_id, status, engine, model, language,
		          text, normalized_text, segments, confidence, error_message,
		          attempt_count, next_retry_at, created_at, updated_at`,
		id, message, retrySeconds, maxAttempts,
	), tr)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	msg, err := updateMessagePayloadFromTranscript(ctx, tx, tr)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return msg, nil
}

func (r *TranscriptRepo) Retry(ctx context.Context, messageID int64) (*Message, error) {
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	tr := &MessageTranscript{}
	err = scanTranscript(tx.QueryRow(ctx, `
		UPDATE message_transcripts
		SET status='pending',
		    error_message=NULL,
		    attempt_count=0,
		    next_retry_at=now(),
		    updated_at=now()
		WHERE message_id=$1
		RETURNING id, message_id, conversation_id, asset_id, status, engine, model, language,
		          text, normalized_text, segments, confidence, error_message,
		          attempt_count, next_retry_at, created_at, updated_at`, messageID), tr)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	msg, err := updateMessagePayloadFromTranscript(ctx, tx, tr)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return msg, nil
}

func updateMessagePayloadFromTranscript(ctx context.Context, tx pgx.Tx, tr *MessageTranscript) (*Message, error) {
	payload, err := updateMessageTranscriptPayload(ctx, tx, tr.MessageID, transcriptPayloadPatch(tr))
	if err != nil {
		return nil, err
	}
	msg := &Message{Payload: payload}
	var senderID sql.NullInt64
	var clientMsgID sql.NullString
	var editedAt, deletedAt sql.NullTime
	err = tx.QueryRow(ctx, `
		SELECT id, conversation_id, sender_id, server_seq, kind, payload, client_msg_id,
		       created_at, edited_at, deleted_at
		FROM messages
		WHERE id=$1`, tr.MessageID).Scan(
		&msg.ID, &msg.ConversationID, &senderID, &msg.ServerSeq, &msg.Kind,
		&msg.Payload, &clientMsgID, &msg.CreatedAt, &editedAt, &deletedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	msg.SenderID = nullInt64Ptr(senderID)
	msg.ClientMsgID = nullStringPtr(clientMsgID)
	msg.EditedAt = nullTimePtr(editedAt)
	msg.DeletedAt = nullTimePtr(deletedAt)
	return msg, nil
}

func updateMessageTranscriptPayload(ctx context.Context, tx pgx.Tx, messageID int64, patch json.RawMessage) (json.RawMessage, error) {
	var payload []byte
	err := tx.QueryRow(ctx, `
		UPDATE messages
		SET payload = payload || $2::jsonb
		WHERE id=$1
		RETURNING payload`, messageID, patch).Scan(&payload)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return json.RawMessage(payload), nil
}

func transcriptPayloadPatch(tr *MessageTranscript) json.RawMessage {
	patch := map[string]any{
		"transcript_status":   tr.Status,
		"transcript_engine":   tr.Engine,
		"transcript_model":    tr.Model,
		"transcript_language": tr.Language,
		"transcript_error":    nil,
	}
	if tr.Text != nil {
		patch["transcript_text"] = *tr.Text
	}
	if tr.NormalizedText != nil {
		patch["transcript_normalized_text"] = *tr.NormalizedText
	}
	if len(tr.Segments) > 0 {
		var segments any
		if err := json.Unmarshal(tr.Segments, &segments); err == nil {
			patch["transcript_segments"] = segments
		}
	}
	if tr.Confidence != nil {
		patch["transcript_confidence"] = *tr.Confidence
	}
	if tr.ErrorMessage != nil {
		patch["transcript_error"] = *tr.ErrorMessage
	}
	raw, _ := json.Marshal(patch)
	return raw
}

func voiceAssetID(payload json.RawMessage) (int64, bool) {
	var obj map[string]any
	if err := json.Unmarshal(payload, &obj); err != nil {
		return 0, false
	}
	if state, _ := obj["media_state"].(string); state != "" && state != "ready" {
		return 0, false
	}
	for _, key := range []string{"asset_id", "assetId"} {
		if id := flexInt64(obj[key]); id > 0 {
			return id, true
		}
	}
	return 0, false
}

func flexInt64(v any) int64 {
	switch x := v.(type) {
	case float64:
		return int64(x)
	case int64:
		return x
	case int:
		return int64(x)
	case json.Number:
		n, _ := x.Int64()
		return n
	case string:
		n, _ := strconv.ParseInt(strings.TrimSpace(x), 10, 64)
		return n
	default:
		return 0
	}
}

type transcriptScanner interface {
	Scan(dest ...any) error
}

func scanTranscript(row transcriptScanner, tr *MessageTranscript) error {
	var text, normalized, errMsg sql.NullString
	var confidence sql.NullFloat64
	var segments []byte
	err := row.Scan(
		&tr.ID, &tr.MessageID, &tr.ConversationID, &tr.AssetID, &tr.Status,
		&tr.Engine, &tr.Model, &tr.Language, &text, &normalized, &segments,
		&confidence, &errMsg, &tr.AttemptCount, &tr.NextRetryAt, &tr.CreatedAt, &tr.UpdatedAt,
	)
	if err != nil {
		return err
	}
	if text.Valid {
		tr.Text = &text.String
	}
	if normalized.Valid {
		tr.NormalizedText = &normalized.String
	}
	if len(segments) == 0 {
		tr.Segments = json.RawMessage(`[]`)
	} else {
		tr.Segments = append(json.RawMessage(nil), segments...)
	}
	if confidence.Valid {
		v := float32(confidence.Float64)
		tr.Confidence = &v
	}
	if errMsg.Valid {
		tr.ErrorMessage = &errMsg.String
	}
	return nil
}

func (t *MessageTranscript) String() string {
	if t == nil {
		return "<nil>"
	}
	return fmt.Sprintf("transcript:%d message:%d status:%s", t.ID, t.MessageID, t.Status)
}
