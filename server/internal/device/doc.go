// Package device — Berxel 相机绑定 + 双摄标定参数版本化云同步（M-S3）。
//
// 通路：
//
//	App ──▶ gateway ──▶ device                    /v1/devices/...
//	admin BFF ──HTTP──▶ device                    /admin/v1/devices（管理员能查所有用户的设备 / 标定）
//
// 数据：
//
//	devices              用户 ↔ 物理相机绑定（serial 全系统 active 唯一）
//	device_calibrations  标定参数不可变历史（version 单调；同 sha256 幂等不 bump）
//
// App 端"扫描启动前比对版本"：GET /v1/devices/{id}/calibrations/latest 返
// (version, sha256, calibrated_at)；端侧本地有同 sha256 → 跳；不一致 → 拉完整 params。
//
// 详见 docs/architecture/server/00-server-overview.md §6.x。
package device
