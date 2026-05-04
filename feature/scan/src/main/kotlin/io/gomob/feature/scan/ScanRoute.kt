package io.gomob.feature.scan

/**
 * 扫描主流程入口（占位）。
 *
 * 后续职责:
 *  - 申请 CAMERA / USB / 通知权限
 *  - 启动 ScanService 前台服务，建立深度相机连接
 *  - Compose UI: 实时预览（RGB + 深度伪彩 + 当前点云覆盖）
 *  - 收尾: 触发 reconstruction 离线重建，跳转到 gallery 详情
 */
const val SCAN_ROUTE = "scan"
