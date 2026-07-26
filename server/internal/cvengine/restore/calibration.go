package restore

import (
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strings"
)

const (
	vinCreatorCalibrationSize    = 2420
	vinCreatorCalibrationVersion = 3

	vinFactoryCalibrationDirEnv = "GOMOB_VIN_FACTORY_CALIBRATION_DIR"
	defaultVinFactoryDir        = "/root/WindowsR"

	factoryDepthWidth    = 1280
	factoryDepthHeight   = 256
	factoryDisparityUnit = 0.125

	factoryBF301208File   = "VIN_BF301208.bin"
	factoryBF301208SHA256 = "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2"
)

var (
	ErrVinCalibrationUnavailable  = errors.New("VIN rig/profile 未发布")
	ErrVinCalibrationAssetInvalid = errors.New("VIN 已发布原厂标定资产无效")
)

// VinCalibrationKey 唯一标识一套双相机 rig 与实际流档位。
// 任一相机、序列号或分辨率变化都不能复用已有外参。
type VinCalibrationKey struct {
	DepthDeviceSerial string `json:"depth_serial"`
	ColorDeviceSerial string `json:"color_serial"`
	DepthWidth        int    `json:"depth_width"`
	DepthHeight       int    `json:"depth_height"`
	ColorWidth        int    `json:"color_width"`
	ColorHeight       int    `json:"color_height"`
}

// VinCalibration 是从 VINCreator 原厂 bin 严格解析出的不可变标定。
// 字段保持包内私有，避免业务层在运行中改写物理参数。
type VinCalibration struct {
	key          VinCalibrationKey
	fileSerial   string
	fileVersion  uint32
	sourceSHA256 string
	color        factoryCameraModel
	depth        factoryDepthModel
}

// AuditIdentity 返回服务端实际采用的原厂文件身份，不暴露可变几何参数。
func (c *VinCalibration) AuditIdentity() (sha256 string, version uint32) {
	if c == nil {
		return "", 0
	}
	return c.sourceSHA256, c.fileVersion
}

// VinCalibrationResolver 按完整 rig/profile 查找服务端权威标定。
type VinCalibrationResolver interface {
	ResolveVinCalibration(VinCalibrationKey) (*VinCalibration, error)
}

type factoryCalibrationSpec struct {
	Key      VinCalibrationKey
	FileName string
	SHA256   string
}

var factoryCalibrationSpecs = []factoryCalibrationSpec{
	{
		Key: VinCalibrationKey{
			DepthDeviceSerial: "BF301208",
			ColorDeviceSerial: "202303111518",
			DepthWidth:        640,
			DepthHeight:       128,
			ColorWidth:        4160,
			ColorHeight:       832,
		},
		FileName: factoryBF301208File,
		SHA256:   factoryBF301208SHA256,
	},
}

type factoryCalibrationResolver struct {
	entries  map[string]*VinCalibration
	loadErrs map[string]error
}

// NewFactoryVinCalibrationResolver 从服务端目录加载白名单原厂标定。
// 标定文件不进入 APK；目录由部署侧挂载，默认读取 /root/WindowsR。
func NewFactoryVinCalibrationResolver(dir string) VinCalibrationResolver {
	dir = strings.TrimSpace(dir)
	if dir == "" {
		dir = defaultVinFactoryDir
	}
	resolver := &factoryCalibrationResolver{
		entries:  make(map[string]*VinCalibration, len(factoryCalibrationSpecs)),
		loadErrs: make(map[string]error, len(factoryCalibrationSpecs)),
	}
	for _, spec := range factoryCalibrationSpecs {
		key := normalizedCalibrationKey(spec.Key)
		mapKey := calibrationMapKey(key)
		calibration, err := loadFactoryVinCalibration(filepath.Join(dir, spec.FileName), key, spec.SHA256)
		if err != nil {
			resolver.loadErrs[mapKey] = err
			continue
		}
		resolver.entries[mapKey] = calibration
	}
	return resolver
}

// NewFactoryVinCalibrationResolverFromEnv 使用服务端环境变量创建 resolver。
func NewFactoryVinCalibrationResolverFromEnv() VinCalibrationResolver {
	return NewFactoryVinCalibrationResolver(os.Getenv(vinFactoryCalibrationDirEnv))
}

// ValidateRequiredFactoryVinCalibrations 确认所有已发布工厂 rig/profile 都可解析使用。
// 容器 readyz 用它阻止“进程健康但 VIN 拍照必然 calibration_unavailable”的假就绪。
func ValidateRequiredFactoryVinCalibrations(resolver VinCalibrationResolver) error {
	if resolver == nil {
		return fmt.Errorf("%w: resolver 未配置", ErrVinCalibrationAssetInvalid)
	}
	for _, spec := range factoryCalibrationSpecs {
		if _, err := resolver.ResolveVinCalibration(spec.Key); err != nil {
			return fmt.Errorf("%s: %w", calibrationMapKey(spec.Key), err)
		}
	}
	return nil
}

func (r *factoryCalibrationResolver) ResolveVinCalibration(key VinCalibrationKey) (*VinCalibration, error) {
	mapKey := calibrationMapKey(normalizedCalibrationKey(key))
	if calibration, ok := r.entries[mapKey]; ok {
		return calibration, nil
	}
	if err, ok := r.loadErrs[mapKey]; ok {
		return nil, fmt.Errorf("%w: %v", ErrVinCalibrationAssetInvalid, err)
	}
	return nil, ErrVinCalibrationUnavailable
}

func normalizedCalibrationKey(key VinCalibrationKey) VinCalibrationKey {
	key.DepthDeviceSerial = strings.ToUpper(strings.TrimSpace(key.DepthDeviceSerial))
	key.ColorDeviceSerial = strings.ToUpper(strings.TrimSpace(key.ColorDeviceSerial))
	return key
}

func calibrationMapKey(key VinCalibrationKey) string {
	return fmt.Sprintf("%s|%s|%dx%d|%dx%d",
		key.DepthDeviceSerial,
		key.ColorDeviceSerial,
		key.DepthWidth,
		key.DepthHeight,
		key.ColorWidth,
		key.ColorHeight,
	)
}

func loadFactoryVinCalibration(path string, key VinCalibrationKey, expectedSHA256 string) (*VinCalibration, error) {
	blob, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("读取 %s: %w", path, err)
	}
	sum := fmt.Sprintf("%x", sha256.Sum256(blob))
	if !strings.EqualFold(sum, strings.TrimSpace(expectedSHA256)) {
		return nil, fmt.Errorf("%s SHA-256 不匹配: got=%s want=%s", filepath.Base(path), sum, expectedSHA256)
	}
	calibration, err := parseVinCreatorCalibration(blob)
	if err != nil {
		return nil, fmt.Errorf("解析 %s: %w", filepath.Base(path), err)
	}
	if !strings.EqualFold(calibration.fileSerial, key.DepthDeviceSerial) {
		return nil, fmt.Errorf("bin 序列号 %s 与 rig 深度序列号 %s 不一致", calibration.fileSerial, key.DepthDeviceSerial)
	}
	calibration.key = key
	calibration.sourceSHA256 = sum
	return calibration, nil
}

// factoryCameraModel 复刻 VINCreator CCameraModel 的私有像素模型。
// 第一像素轴是 JPEG 行，第二像素轴是 JPEG 列，不能按普通 OpenCV (u,v) 顺序套用。
type factoryCameraModel struct {
	PrincipalRow    float64
	PrincipalColumn float64
	FocalRow        float64
	FocalColumn     float64
	Distortion      [5]float64
	Euler           [3]float64
	Rotation        [3][3]float64
	Translation     Vec3
}

type factoryDepthModel struct {
	PrincipalColumn float64
	PrincipalRow    float64
	Focal           float64
	BaselineMM      float64
	DataType        uint32
}

func parseVinCreatorCalibration(blob []byte) (*VinCalibration, error) {
	if len(blob) != vinCreatorCalibrationSize {
		return nil, fmt.Errorf("文件大小=%d，期望 %d", len(blob), vinCreatorCalibrationSize)
	}
	// 前 256 字节是原厂忽略的元数据缓冲；仅取其固定首 8 字节做部署白名单的序列号加固。
	serial := string(blob[:8])
	if len(serial) != 8 || !strings.HasPrefix(strings.ToUpper(serial), "BF") {
		return nil, fmt.Errorf("设备序列号非法: %q", serial)
	}
	// 原厂 loader 唯一显式内容校验是 payload 0x200 的 format version。
	version := binary.LittleEndian.Uint32(blob[0x200:])
	if version != vinCreatorCalibrationVersion {
		return nil, fmt.Errorf("版本=%d，期望 %d", version, vinCreatorCalibrationVersion)
	}
	if reserved0, reserved1 := binary.LittleEndian.Uint32(blob[0x204:]), binary.LittleEndian.Uint32(blob[0x208:]); reserved0 != 0 || reserved1 != 0 {
		return nil, fmt.Errorf("payload 保留字段非法: %d %d", reserved0, reserved1)
	}
	for _, field := range []struct {
		name   string
		offset int
	}{
		{name: "相机 A 畸变组数", offset: 0x234},
		{name: "相机 A 姿态组数", offset: 0x260},
		{name: "相机 B 畸变组数", offset: 0x2bc},
		{name: "相机 B 姿态组数", offset: 0x2e8},
		{name: "保留向量组数", offset: 0x31c},
	} {
		if count := binary.LittleEndian.Uint32(blob[field.offset:]); count != 1 {
			return nil, fmt.Errorf("%s=%d，期望 1", field.name, count)
		}
	}

	color, err := parseFactoryCameraBlock(blob, 0x20c)
	if err != nil {
		return nil, err
	}
	duplicate, err := parseFactoryCameraBlock(blob, 0x294)
	if err != nil {
		return nil, err
	}
	if !sameFactoryCameraModel(color, duplicate, 1e-12) {
		return nil, errors.New("前后相机模型块不一致，拒绝猜测使用哪一块")
	}

	if scaleX, scaleY := readFloat32(blob, 0x338), readFloat32(blob, 0x33c); !almostEqual(scaleX, 1, 1e-6) || !almostEqual(scaleY, 1, 1e-6) {
		return nil, fmt.Errorf("深度缩放字段非法: %.9g %.9g", scaleX, scaleY)
	}
	depth := factoryDepthModel{
		PrincipalColumn: readFloat32(blob, 0x340),
		PrincipalRow:    readFloat32(blob, 0x344),
		Focal:           readFloat32(blob, 0x348),
		BaselineMM:      readFloat32(blob, 0x35c),
		DataType:        binary.LittleEndian.Uint32(blob[0x360:]),
	}
	if err := validateFactoryDepthModel(blob, depth); err != nil {
		return nil, err
	}
	return &VinCalibration{
		fileSerial:  strings.ToUpper(serial),
		fileVersion: version,
		color:       color,
		depth:       depth,
	}, nil
}

func parseFactoryCameraBlock(blob []byte, base int) (factoryCameraModel, error) {
	model := factoryCameraModel{
		PrincipalRow:    readFloat64(blob, base),
		PrincipalColumn: readFloat64(blob, base+8),
		FocalRow:        readFloat64(blob, base+16),
		FocalColumn:     readFloat64(blob, base+24),
	}
	distBase := base + 44
	for i := range model.Distortion {
		model.Distortion[i] = readFloat64(blob, distBase+i*8)
	}
	eulerBase := base + 88
	for i := range model.Euler {
		model.Euler[i] = readFloat64(blob, eulerBase+i*8)
	}
	translationBase := base + 112
	for i := range model.Translation {
		model.Translation[i] = readFloat64(blob, translationBase+i*8)
	}
	if err := validateFactoryCameraModel(model); err != nil {
		return factoryCameraModel{}, fmt.Errorf("相机模型块 0x%x: %w", base, err)
	}
	model.Rotation = factoryEulerRotation(model.Euler)
	return model, nil
}

func validateFactoryCameraModel(model factoryCameraModel) error {
	values := []float64{model.PrincipalRow, model.PrincipalColumn, model.FocalRow, model.FocalColumn}
	values = append(values, model.Distortion[:]...)
	values = append(values, model.Euler[:]...)
	values = append(values, model.Translation[:]...)
	for _, value := range values {
		if !isFinite(value) {
			return errors.New("包含 NaN/Inf")
		}
	}
	if model.PrincipalRow < 0 || model.PrincipalRow > 5000 ||
		model.PrincipalColumn < 0 || model.PrincipalColumn > 5000 ||
		model.FocalRow < 1000 || model.FocalRow > 10000 ||
		model.FocalColumn < 1000 || model.FocalColumn > 10000 {
		return fmt.Errorf("内参超出合理范围: row=%.6f col=%.6f fr=%.6f fc=%.6f",
			model.PrincipalRow, model.PrincipalColumn, model.FocalRow, model.FocalColumn)
	}
	for _, angle := range model.Euler {
		if math.Abs(angle) > 2*math.Pi {
			return fmt.Errorf("Euler 角超出范围: %.9f", angle)
		}
	}
	for _, value := range model.Translation {
		if math.Abs(value) > 200 {
			return fmt.Errorf("平移超出 200mm: %.9f", value)
		}
	}
	return nil
}

func validateFactoryDepthModel(blob []byte, model factoryDepthModel) error {
	values := []float64{model.PrincipalColumn, model.PrincipalRow, model.Focal, model.BaselineMM}
	for _, value := range values {
		if !isFinite(value) {
			return errors.New("深度模型包含 NaN/Inf")
		}
	}
	if model.PrincipalColumn <= 0 || model.PrincipalColumn >= factoryDepthWidth ||
		model.PrincipalRow <= 0 || model.PrincipalRow >= factoryDepthHeight ||
		model.Focal < 500 || model.Focal > 3000 ||
		model.BaselineMM < 40 || model.BaselineMM > 60 {
		return fmt.Errorf("mode25 深度参数超出合理范围: cx=%.6f cy=%.6f f=%.6f B=%.6f",
			model.PrincipalColumn, model.PrincipalRow, model.Focal, model.BaselineMM)
	}
	if model.DataType != 1 {
		return fmt.Errorf("mode25 深度数据类型=%d，期望 raw disparity×8 类型 1", model.DataType)
	}
	if !almostEqual(readFloat32(blob, 0x34c), model.PrincipalColumn, 1e-4) ||
		!almostEqual(readFloat32(blob, 0x354), model.Focal, 1e-4) ||
		!almostEqual(readFloat32(blob, 0x358), model.Focal, 1e-4) {
		return errors.New("mode25/全幅深度焦距字段不自洽")
	}
	fullRow := readFloat32(blob, 0x350)
	if fullRow <= model.PrincipalRow || fullRow >= 960 {
		return fmt.Errorf("深度全幅主点非法: %.6f", fullRow)
	}
	return nil
}

func sameFactoryCameraModel(a, b factoryCameraModel, tolerance float64) bool {
	av := []float64{a.PrincipalRow, a.PrincipalColumn, a.FocalRow, a.FocalColumn}
	bv := []float64{b.PrincipalRow, b.PrincipalColumn, b.FocalRow, b.FocalColumn}
	av = append(av, a.Distortion[:]...)
	bv = append(bv, b.Distortion[:]...)
	av = append(av, a.Euler[:]...)
	bv = append(bv, b.Euler[:]...)
	av = append(av, a.Translation[:]...)
	bv = append(bv, b.Translation[:]...)
	for i := range av {
		if !almostEqual(av[i], bv[i], tolerance) {
			return false
		}
	}
	return true
}

func readFloat64(blob []byte, offset int) float64 {
	return math.Float64frombits(binary.LittleEndian.Uint64(blob[offset:]))
}

func readFloat32(blob []byte, offset int) float64 {
	return float64(math.Float32frombits(binary.LittleEndian.Uint32(blob[offset:])))
}

func almostEqual(a, b, tolerance float64) bool {
	return math.Abs(a-b) <= tolerance
}

// 原厂 RotationMatrix 的 Euler 顺序为 Rz(e2)·Rx(e1)·Ry(e0)。
func factoryEulerRotation(euler [3]float64) [3][3]float64 {
	ry := [3][3]float64{
		{math.Cos(euler[0]), 0, math.Sin(euler[0])},
		{0, 1, 0},
		{-math.Sin(euler[0]), 0, math.Cos(euler[0])},
	}
	rx := [3][3]float64{
		{1, 0, 0},
		{0, math.Cos(euler[1]), -math.Sin(euler[1])},
		{0, math.Sin(euler[1]), math.Cos(euler[1])},
	}
	rz := [3][3]float64{
		{math.Cos(euler[2]), -math.Sin(euler[2]), 0},
		{math.Sin(euler[2]), math.Cos(euler[2]), 0},
		{0, 0, 1},
	}
	return multiplyMat3(multiplyMat3(rz, rx), ry)
}

func multiplyMat3(a, b [3][3]float64) [3][3]float64 {
	var out [3][3]float64
	for row := range out {
		for column := range out[row] {
			for k := 0; k < 3; k++ {
				out[row][column] += a[row][k] * b[k][column]
			}
		}
	}
	return out
}
