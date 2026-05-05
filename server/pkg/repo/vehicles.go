package repo

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Vehicle — vehicles 表的 Go 映射。
type Vehicle struct {
	ID          int64
	VIN         string
	PlateNo     *string
	Brand       *string
	Type        *string
	ModelCode   *string
	YearCode    *string
	FactoryDate *time.Time
	Color       *string
	CreatedAt   time.Time
}

type VehicleRepo struct {
	pool *pgxpool.Pool
}

func NewVehicleRepo(pool *pgxpool.Pool) *VehicleRepo {
	return &VehicleRepo{pool: pool}
}

// Upsert 按 VIN 唯一约束写入或返回已有；调用方仅传 VIN + 可选字段。
func (r *VehicleRepo) Upsert(ctx context.Context, v *Vehicle) error {
	const q = `
		INSERT INTO vehicles (vin, plate_no, brand, type, model_code, year_code, factory_date, color)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
		ON CONFLICT (vin) DO UPDATE
		SET plate_no    = COALESCE(EXCLUDED.plate_no,    vehicles.plate_no),
		    brand       = COALESCE(EXCLUDED.brand,       vehicles.brand),
		    type        = COALESCE(EXCLUDED.type,        vehicles.type),
		    model_code  = COALESCE(EXCLUDED.model_code,  vehicles.model_code),
		    year_code   = COALESCE(EXCLUDED.year_code,   vehicles.year_code),
		    factory_date= COALESCE(EXCLUDED.factory_date,vehicles.factory_date),
		    color       = COALESCE(EXCLUDED.color,       vehicles.color)
		RETURNING id, created_at`
	return r.pool.QueryRow(ctx, q,
		v.VIN, v.PlateNo, v.Brand, v.Type, v.ModelCode, v.YearCode, v.FactoryDate, v.Color,
	).Scan(&v.ID, &v.CreatedAt)
}

func (r *VehicleRepo) FindByVIN(ctx context.Context, vin string) (*Vehicle, error) {
	const q = `
		SELECT id, vin, plate_no, brand, type, model_code, year_code, factory_date, color, created_at
		FROM vehicles WHERE vin = $1`
	row := r.pool.QueryRow(ctx, q, vin)
	v := &Vehicle{}
	if err := row.Scan(&v.ID, &v.VIN, &v.PlateNo, &v.Brand, &v.Type, &v.ModelCode,
		&v.YearCode, &v.FactoryDate, &v.Color, &v.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return v, nil
}

func (r *VehicleRepo) FindByID(ctx context.Context, id int64) (*Vehicle, error) {
	const q = `
		SELECT id, vin, plate_no, brand, type, model_code, year_code, factory_date, color, created_at
		FROM vehicles WHERE id = $1`
	row := r.pool.QueryRow(ctx, q, id)
	v := &Vehicle{}
	if err := row.Scan(&v.ID, &v.VIN, &v.PlateNo, &v.Brand, &v.Type, &v.ModelCode,
		&v.YearCode, &v.FactoryDate, &v.Color, &v.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return v, nil
}
