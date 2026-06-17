// C-ABI 包装：激光双单元车辆外廓「连接+采集+融合」，供 gomob 服务端 cmd/laserworker 经 cgo 调用。
// 关键=逐帧点回调 on_points 支撑流式（采集中实时推点 + 融合后整云）。点单位 **毫米(mm)**。
// 现有 captureSweep + cloud/fusion + cloud/registration 复用，无新几何。线程安全：一次一个扫描会话。
#ifndef LIDAR_SCAN_H
#define LIDAR_SCAN_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// unit: 0=unitA 原始, 1=unitB 原始, 2=融合结果。xyz_mm=[x,y,z,...] mm, n=点数（三元组个数）。
typedef void (*LidarPointCB)(void* user, int unit, const float* xyz_mm, int n, float h_angle_deg);
// 彩色点云回调：rgb 为 0xRRGGBB，每点一项；mapped=实际投影到相机像素的点数（其余为灰色）。
typedef void (*LidarColorPointCB)(void* user, int unit, const float* xyz_mm, const uint32_t* rgb, int n, int mapped);
// state: "connecting"|"scanning"|"fusing"|"done"|"error"|"cancelled"。
typedef void (*LidarStatusCB)(void* user, const char* state, int frames_a, int frames_b);
// 实时 RGB 预览回调：扫描中每收到一帧相机图，降采样后回调一次 JPEG 字节（端侧小窗预览）。
// unit 0=A/1=B；jpeg 仅本次回调有效（须立即拷出）。
typedef void (*LidarImageCB)(void* user, int unit, const uint8_t* jpeg, int jpeg_len, float h_angle_deg);

typedef struct {
    int   pts_a, pts_b, fused, after_crop;
    float b_to_a[16];     // 4x4 行优先（mm 平移）
    char  align[16];      // "site"|"icp"|"none"|"raw"
    char  error[128];     // 失败原因（成功为空）
} LidarScanResult;

// 实时扫描（连接 .101/.102 + 采集 + 融合）。align: "site"(读 site_json 4x4)|"raw"(只采 A/B，不融合)。
// 阻塞至完成/错误/取消。返回 0=成功。on_points/on_status 可为 NULL。out 可为 NULL。
int lidar_scan_live(const char* ipA, const char* ipB, const char* align, const char* site_json,
                    float keep_ratio, LidarPointCB on_points, LidarStatusCB on_status,
                    void* user, LidarScanResult* out);

// 扩展实时扫描：当 on_color_points 非空，且 102 相机 IMG 流与纹理配置可用时，额外回调 unit=1 的彩色点云。
int lidar_scan_live_ex(const char* ipA, const char* ipB, const char* align, const char* site_json,
                       float keep_ratio, LidarPointCB on_points, LidarColorPointCB on_color_points,
                       LidarStatusCB on_status, void* user, LidarScanResult* out);

// 带预期扫掠角的扩展实时扫描。expected_sweep_* 仅作为日志/诊断上下文；live 采集不再用角度跨度
// 提前终止或判失败，避免控制板状态角/PTS 局部角基准不一致时丢掉真实点云。
int lidar_scan_live_configured_ex(const char* ipA, const char* ipB, const char* align, const char* site_json,
                                  float keep_ratio, float expected_sweep_a_deg, float expected_sweep_b_deg,
                                  LidarPointCB on_points, LidarColorPointCB on_color_points,
                                  LidarStatusCB on_status, void* user, LidarScanResult* out);

// 离线回放（host 测试 / 无硬件）：binA/binB = 录制的 PTS 原始流文件。其余同上。
int lidar_scan_replay(const char* binA, const char* binB, const char* align, const char* site_json,
                      float keep_ratio, LidarPointCB on_points, LidarStatusCB on_status,
                      void* user, LidarScanResult* out);

int lidar_scan_replay_ex(const char* binA, const char* binB, const char* align, const char* site_json,
                         float keep_ratio, LidarPointCB on_points, LidarColorPointCB on_color_points,
                         LidarStatusCB on_status, void* user, LidarScanResult* out);

// 注册实时图像预览回调：lidar_scan_live_* 期间每收到一帧相机图就降采样回调。cb=NULL 关闭。
// 纯增量、不改既有入口签名；一次一个扫描会话，全局生效（线程安全由"一次一会话"约束保证）。
void lidar_scan_set_preview_cb(LidarImageCB cb, void* user);

// 协作取消当前会话（停采集；live 下还会让设备 SCAN_STOP 由上层处理）。
void lidar_scan_cancel(void);

#ifdef __cplusplus
}
#endif
#endif  // LIDAR_SCAN_H
