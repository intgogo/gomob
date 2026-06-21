package repo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type MediaRoom struct {
	ID           int64
	Provider     string
	ProviderRoom string
	Kind         string
	SubjectKind  *string
	SubjectID    *int64
	CreatedBy    int64
	Status       string
	StartedAt    *time.Time
	EndedAt      *time.Time
	Metadata     json.RawMessage
	CreatedAt    time.Time
}

type MediaParticipant struct {
	RoomID   int64
	UserID   int64
	Role     string
	JoinedAt *time.Time
	LeftAt   *time.Time
}

type LiveSession struct {
	ID                    int64
	MediaRoomID           int64
	InspectionID          *int64
	PublisherID           int64
	StationID             *int64
	Title                 string
	Status                string
	StartedAt             *time.Time
	EndedAt               *time.Time
	LatestSnapshotAssetID *int64
	Metadata              json.RawMessage
	CreatedAt             time.Time
}

type CallLog struct {
	ID             int64
	RoomID         *int64
	ConversationID *int64
	CallerID       int64
	CalleeID       int64
	StartedAt      time.Time
	EndedAt        *time.Time
	DurationSec    int
	Status         string
}

type MediaRepo struct {
	pool *pgxpool.Pool
}

func NewMediaRepo(pool *pgxpool.Pool) *MediaRepo {
	return &MediaRepo{pool: pool}
}

func (r *MediaRepo) CreateRoom(ctx context.Context, room *MediaRoom, participants []MediaParticipant) error {
	if len(room.Metadata) == 0 {
		room.Metadata = json.RawMessage(`{}`)
	}
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	const q = `
		INSERT INTO media_rooms(provider, provider_room, kind, subject_kind, subject_id, created_by, status, started_at, metadata)
		VALUES('livekit', $1, $2, $3, $4, $5, $6, CASE WHEN $6='active' THEN now() ELSE NULL END, $7)
		RETURNING id, provider, created_at, started_at`
	var startedAt sql.NullTime
	if err := tx.QueryRow(ctx, q,
		room.ProviderRoom, room.Kind, room.SubjectKind, room.SubjectID,
		room.CreatedBy, room.Status, room.Metadata,
	).Scan(&room.ID, &room.Provider, &room.CreatedAt, &startedAt); err != nil {
		return err
	}
	room.StartedAt = nullTimePtr(startedAt)
	for _, p := range participants {
		if p.UserID <= 0 || p.Role == "" {
			continue
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO media_participants(room_id, user_id, role, joined_at)
			VALUES($1, $2, $3, $4)
			ON CONFLICT (room_id, user_id, role) DO NOTHING`,
			room.ID, p.UserID, p.Role, p.JoinedAt,
		); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (r *MediaRepo) FindRoom(ctx context.Context, id int64) (*MediaRoom, error) {
	const q = `
		SELECT id, provider, provider_room, kind, subject_kind, subject_id, created_by,
		       status, started_at, ended_at, metadata, created_at
		FROM media_rooms WHERE id=$1`
	var room MediaRoom
	var subjectKind sql.NullString
	var subjectID sql.NullInt64
	var startedAt, endedAt sql.NullTime
	if err := r.pool.QueryRow(ctx, q, id).Scan(
		&room.ID, &room.Provider, &room.ProviderRoom, &room.Kind,
		&subjectKind, &subjectID, &room.CreatedBy, &room.Status,
		&startedAt, &endedAt, &room.Metadata, &room.CreatedAt,
	); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	room.SubjectKind = nullStringPtr(subjectKind)
	room.SubjectID = nullInt64Ptr(subjectID)
	room.StartedAt = nullTimePtr(startedAt)
	room.EndedAt = nullTimePtr(endedAt)
	return &room, nil
}

func (r *MediaRepo) SetRoomStatus(ctx context.Context, roomID int64, status string, metadata json.RawMessage) error {
	if len(metadata) == 0 {
		metadata = json.RawMessage(`{}`)
	}
	const q = `
		UPDATE media_rooms
		SET status=$2,
		    started_at=CASE WHEN $2='active' THEN COALESCE(started_at, now()) ELSE started_at END,
		    ended_at=CASE WHEN $2 IN ('ended', 'failed') THEN COALESCE(ended_at, now()) ELSE ended_at END,
		    metadata=$3
		WHERE id=$1`
	tag, err := r.pool.Exec(ctx, q, roomID, status, metadata)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (r *MediaRepo) UpsertParticipant(ctx context.Context, p MediaParticipant) error {
	const q = `
		INSERT INTO media_participants(room_id, user_id, role, joined_at, left_at)
		VALUES($1, $2, $3, $4, $5)
		ON CONFLICT (room_id, user_id, role) DO UPDATE
		SET joined_at=COALESCE(media_participants.joined_at, EXCLUDED.joined_at),
		    left_at=EXCLUDED.left_at`
	_, err := r.pool.Exec(ctx, q, p.RoomID, p.UserID, p.Role, p.JoinedAt, p.LeftAt)
	return err
}

func (r *MediaRepo) IsParticipant(ctx context.Context, roomID, userID int64) (bool, error) {
	var exists bool
	err := r.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM media_participants WHERE room_id=$1 AND user_id=$2)`,
		roomID, userID,
	).Scan(&exists)
	return exists, err
}

func (r *MediaRepo) HasJoinedCounterpart(ctx context.Context, roomID, callerUserID int64) (bool, error) {
	var exists bool
	err := r.pool.QueryRow(ctx,
		`SELECT EXISTS(
			SELECT 1
			FROM media_participants
			WHERE room_id=$1 AND user_id<>$2 AND joined_at IS NOT NULL
		)`,
		roomID, callerUserID,
	).Scan(&exists)
	return exists, err
}

func (r *MediaRepo) InsertCallLogOnce(ctx context.Context, log *CallLog) (bool, error) {
	if log == nil {
		return false, errors.New("call log is nil")
	}
	const q = `
		INSERT INTO call_logs(room_id, conversation_id, caller_id, callee_id, started_at, ended_at, duration_sec, status)
		VALUES($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (room_id) DO NOTHING
		RETURNING id`
	err := r.pool.QueryRow(ctx, q,
		log.RoomID, log.ConversationID, log.CallerID, log.CalleeID,
		log.StartedAt, log.EndedAt, log.DurationSec, log.Status,
	).Scan(&log.ID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	return true, nil
}

func (r *MediaRepo) CreateLiveSession(ctx context.Context, session *LiveSession) error {
	if len(session.Metadata) == 0 {
		session.Metadata = json.RawMessage(`{}`)
	}
	const q = `
		INSERT INTO live_sessions(media_room_id, inspection_id, publisher_id, station_id, title, status, started_at, metadata)
		VALUES($1, $2, $3, $4, $5, $6, CASE WHEN $6='live' THEN now() ELSE NULL END, $7)
		RETURNING id, created_at, started_at`
	var startedAt sql.NullTime
	if err := r.pool.QueryRow(ctx, q,
		session.MediaRoomID, session.InspectionID, session.PublisherID, session.StationID,
		session.Title, session.Status, session.Metadata,
	).Scan(&session.ID, &session.CreatedAt, &startedAt); err != nil {
		return err
	}
	session.StartedAt = nullTimePtr(startedAt)
	return nil
}

// FindLiveSessionByProviderRoom 按 LiveKit provider room 名反查 live_session（经 media_rooms 关联）。
// 无命中返 ErrNotFound（对齐本包既有用法），webhook 侧据此判定"未知房间"并仅记日志后 ACK。
func (r *MediaRepo) FindLiveSessionByProviderRoom(ctx context.Context, providerRoom string) (*LiveSession, error) {
	const q = `
		SELECT live_sessions.id, live_sessions.media_room_id, live_sessions.inspection_id,
		       live_sessions.publisher_id, live_sessions.station_id, live_sessions.title,
		       live_sessions.status, live_sessions.started_at, live_sessions.ended_at,
		       live_sessions.latest_snapshot_asset_id, live_sessions.metadata, live_sessions.created_at
		FROM live_sessions
		JOIN media_rooms ON live_sessions.media_room_id = media_rooms.id
		WHERE media_rooms.provider_room = $1
		LIMIT 1`
	var s LiveSession
	var inspectionID, stationID, snapshotID sql.NullInt64
	var startedAt, endedAt sql.NullTime
	if err := r.pool.QueryRow(ctx, q, providerRoom).Scan(
		&s.ID, &s.MediaRoomID, &inspectionID, &s.PublisherID, &stationID,
		&s.Title, &s.Status, &startedAt, &endedAt, &snapshotID, &s.Metadata, &s.CreatedAt,
	); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	s.InspectionID = nullInt64Ptr(inspectionID)
	s.StationID = nullInt64Ptr(stationID)
	s.LatestSnapshotAssetID = nullInt64Ptr(snapshotID)
	s.StartedAt = nullTimePtr(startedAt)
	s.EndedAt = nullTimePtr(endedAt)
	return &s, nil
}

// MarkLiveSessionLive 把 created 态会话推进到 live（room_started webhook 驱动，幂等）。
// 仅当当前 status='created' 时生效；已 live/ended 的重复事件不回退，RowsAffected=0 即无操作。
func (r *MediaRepo) MarkLiveSessionLive(ctx context.Context, id int64) error {
	const q = `
		UPDATE live_sessions
		SET status='live', started_at=COALESCE(started_at, now())
		WHERE id=$1 AND status='created'`
	_, err := r.pool.Exec(ctx, q, id)
	return err
}

// EndLiveSession 收尾会话（room_finished webhook 驱动，幂等）。
// 仅当 status<>'ended' 时生效，避免重复事件覆盖 ended_at。
func (r *MediaRepo) EndLiveSession(ctx context.Context, id int64) error {
	const q = `
		UPDATE live_sessions
		SET status='ended', ended_at=now()
		WHERE id=$1 AND status<>'ended'`
	_, err := r.pool.Exec(ctx, q, id)
	return err
}

func (r *MediaRepo) ListLiveSessions(ctx context.Context, status string, limit int) ([]LiveSession, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	const q = `
		SELECT id, media_room_id, inspection_id, publisher_id, station_id, title, status,
		       started_at, ended_at, latest_snapshot_asset_id, metadata, created_at
		FROM live_sessions
		WHERE ($1='' OR status=$1)
		ORDER BY created_at DESC, id DESC
		LIMIT $2`
	rows, err := r.pool.Query(ctx, q, status, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := make([]LiveSession, 0, limit)
	for rows.Next() {
		var s LiveSession
		var inspectionID, stationID, snapshotID sql.NullInt64
		var startedAt, endedAt sql.NullTime
		if err := rows.Scan(
			&s.ID, &s.MediaRoomID, &inspectionID, &s.PublisherID, &stationID,
			&s.Title, &s.Status, &startedAt, &endedAt, &snapshotID, &s.Metadata, &s.CreatedAt,
		); err != nil {
			return nil, err
		}
		s.InspectionID = nullInt64Ptr(inspectionID)
		s.StationID = nullInt64Ptr(stationID)
		s.LatestSnapshotAssetID = nullInt64Ptr(snapshotID)
		s.StartedAt = nullTimePtr(startedAt)
		s.EndedAt = nullTimePtr(endedAt)
		items = append(items, s)
	}
	return items, rows.Err()
}
