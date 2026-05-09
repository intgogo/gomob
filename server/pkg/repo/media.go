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
