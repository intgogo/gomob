// eYs3D RS-D550 深度度量转换(硬件 ASIC 路径)。纯 portable,无 libusb / 无 OpenCV。
//
// 数据流(复刻 APK,见 docs/architecture/13-eys3d-driver.md §2bis):
//   设备 ASIC 经 IF2 推 11-bit 视差帧(每像素 1 个小端 u16, 有效 0..2047)
//     → (可选)视差线性补偿 new = disp*scale + bias
//     → ZD 表查表 Z_mm = ZDtable[disp]   (厂家 flash LUT, 已含 fx·B/disp + 非线性/温补修正)
//   退化路径(无 ZD 表): Z_mm = fx'·B / (disp/subpixel)  几何反投影。
//
// ZD 表来源:设备 flash file id = 50 + nIndex, 经 APC_GetZDTable 取(BufferLength 必须=4096)。
//   厂家按相机字节序(大端)存,读出每 u16 需 byteswap。11-bit 模式 4096B = 2048×u16, 值即 mm。
#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>

namespace gomob::eys3d {

// 11-bit 视差域 ZD 表项数(2^11)。8-bit 模式为 256。
constexpr size_t kZdTableEntries11Bit = 2048;
constexpr size_t kZdTableEntries8Bit = 256;
constexpr float kDefaultSubpixel = 8.0f;  // 11-bit 视差 = 真视差 × 8

// 视差(u16)→ metric Z(mm) 查找表。
class ZdTable {
 public:
  ZdTable() = default;

  // 从设备 flash blob 构造:data 是 APC_GetZDTable 出的原始字节(大端 u16 序列)。
  // size 应为 4096(2048 项)或 512(256 项)。内部做 byteswap 转主机序。
  static ZdTable FromFlashBlob(const uint8_t* data, size_t size);

  // 从已是主机序的 mm 数组直接构造(测试/已解析场景)。
  static ZdTable FromMillimeters(std::vector<uint16_t> mm);

  bool valid() const { return !mm_.empty(); }
  size_t size() const { return mm_.size(); }
  const uint16_t* data() const { return mm_.data(); }

  // 单点查表。disparity 越界 → 返回 0(无效深度)。
  uint16_t ZMm(uint16_t disparity) const {
    return disparity < mm_.size() ? mm_[disparity] : 0;
  }

  // 整帧转换:disp(u16 视差帧, count 个像素)→ out_mm(u16 metric 帧, count 个)。
  // comp_scale/comp_bias:视差线性补偿(来自 RectLogData.depth_comp_pars),默认不补偿。
  // 越界/0 视差 → out 该像素=0。out_mm 必须可容纳 count 个 u16。
  void DisparityToDepthMm(const uint16_t* disp, size_t count, uint16_t* out_mm,
                          float comp_scale = 1.0f, float comp_bias = 0.0f) const;

 private:
  explicit ZdTable(std::vector<uint16_t> mm) : mm_(std::move(mm)) {}
  std::vector<uint16_t> mm_;  // mm_[disparity] = Z(mm)
};

// 几何退化:无 ZD 表时按 Z = fx'·baseline / 真视差 反投影。
// disparity_u16 是 IF2 的整数视差(× subpixel);fx' 用对应深度分辨率的矫正焦距(NewCamMat1[0])。
// 返回 mm,clamp 到 [0, 65535];视差<=0 或结果非法 → 0。
uint16_t GeometricZMm(uint16_t disparity_u16, float fx_rect, float baseline_mm,
                      float subpixel = kDefaultSubpixel);

// 整帧几何转换(无表 fallback)。
void GeometricDisparityToDepthMm(const uint16_t* disp, size_t count, uint16_t* out_mm,
                                 float fx_rect, float baseline_mm,
                                 float subpixel = kDefaultSubpixel);

}  // namespace gomob::eys3d
