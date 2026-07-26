package restore

import "errors"

const (
	vinPreviewContractVersion = 1
	vinPreviewProjectionModel = "vincreator_factory_v3"
	vinPreviewSampleFormat    = "disparity_x8_u16"
	vinPreviewOcclusionMetric = "absolute_camera_z"
)

// VinPreviewCalibration 是手机实时预览使用的只读原厂投影快照。
// 固定长度数组均按值复制，调用方不能借由响应改写 resolver 内的物理标定。
type VinPreviewCalibration struct {
	ContractVersion    int                        `json:"contract_version"`
	ProjectionModel    string                     `json:"projection_model"`
	OcclusionMetric    string                     `json:"occlusion_metric"`
	Key                VinCalibrationKey          `json:"key"`
	CalibrationSHA256  string                     `json:"calibration_sha256"`
	CalibrationVersion uint32                     `json:"calibration_version"`
	Depth              VinPreviewDepthCalibration `json:"depth"`
	Color              VinPreviewColorCalibration `json:"color"`
}

// VinPreviewDepthCalibration 明确区分 Z 反算焦距与当前档位 XY 反投影焦距。
type VinPreviewDepthCalibration struct {
	SampleFormat     string  `json:"sample_format"`
	DataType         uint32  `json:"data_type"`
	ReferenceWidth   int     `json:"reference_width"`
	ReferenceHeight  int     `json:"reference_height"`
	PrincipalColumn  float64 `json:"principal_column"`
	PrincipalRow     float64 `json:"principal_row"`
	ProjectionFocalX float64 `json:"projection_focal_x"`
	ProjectionFocalY float64 `json:"projection_focal_y"`
	DisparityFocal   float64 `json:"disparity_focal"`
	BaselineMM       float64 `json:"baseline_mm"`
	DisparityUnit    float64 `json:"disparity_unit"`
	ValidDepthMinMM  float64 `json:"valid_depth_min_mm"`
	ValidDepthMaxMM  float64 `json:"valid_depth_max_mm"`
}

// VinPreviewColorCalibration 保留 VINCreator 的行/列轴顺序与私有像素畸变模型。
type VinPreviewColorCalibration struct {
	PrincipalRow    float64    `json:"principal_row"`
	PrincipalColumn float64    `json:"principal_column"`
	FocalRow        float64    `json:"focal_row"`
	FocalColumn     float64    `json:"focal_column"`
	Distortion      [5]float64 `json:"distortion_pixel_k_p1_p2_s1_s2"`
	Rotation        [9]float64 `json:"rotation_row_major"`
	TranslationMM   [3]float64 `json:"translation_mm"`
}

// PreviewProjection 导出手机预览所需的完整原厂几何，不暴露可变内部对象。
func (c *VinCalibration) PreviewProjection() (VinPreviewCalibration, error) {
	if c == nil {
		return VinPreviewCalibration{}, errors.New("VIN 标定为空")
	}
	fx, fy, cx, cy, err := c.depth.intrinsicsForProfile(c.key.DepthWidth, c.key.DepthHeight)
	if err != nil {
		return VinPreviewCalibration{}, err
	}
	rotation := [9]float64{}
	for row := range c.color.Rotation {
		for column := range c.color.Rotation[row] {
			rotation[row*3+column] = c.color.Rotation[row][column]
		}
	}
	return VinPreviewCalibration{
		ContractVersion:    vinPreviewContractVersion,
		ProjectionModel:    vinPreviewProjectionModel,
		OcclusionMetric:    vinPreviewOcclusionMetric,
		Key:                c.key,
		CalibrationSHA256:  c.sourceSHA256,
		CalibrationVersion: c.fileVersion,
		Depth: VinPreviewDepthCalibration{
			SampleFormat:     vinPreviewSampleFormat,
			DataType:         c.depth.DataType,
			ReferenceWidth:   factoryDepthWidth,
			ReferenceHeight:  factoryDepthHeight,
			PrincipalColumn:  cx,
			PrincipalRow:     cy,
			ProjectionFocalX: fx,
			ProjectionFocalY: fy,
			DisparityFocal:   c.depth.Focal,
			BaselineMM:       c.depth.BaselineMM,
			DisparityUnit:    factoryDisparityUnit,
			ValidDepthMinMM:  factoryDepthMinMM,
			ValidDepthMaxMM:  factoryDepthMaxMM,
		},
		Color: VinPreviewColorCalibration{
			PrincipalRow:    c.color.PrincipalRow,
			PrincipalColumn: c.color.PrincipalColumn,
			FocalRow:        c.color.FocalRow,
			FocalColumn:     c.color.FocalColumn,
			Distortion:      c.color.Distortion,
			Rotation:        rotation,
			TranslationMM:   [3]float64(c.color.Translation),
		},
	}, nil
}
