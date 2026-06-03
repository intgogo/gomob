# 车辆外廓扫描 + VIN 数码拓印端到端拉通（2026-06-02）

## Why

底座（`CameraSource.active()` 真 16bit metric depth + confidence，2510DRK44C 验过）与服务端（`scan3d_bundle`→融合 worker→`/fuse`→GLB→`scan.fusion_done`；cvengine `vin_pipeline` verdict）早已就绪，但两个用户功能的入口卡片指向**纯 mock 屏**（`VehicleContourScanScreen` 硬编码 `shots`、`ScanCaptureScreen` 硬编码 `VinValue`），真录制管线 `scan3d/recording` 是**孤儿路由**。缺口集中在端侧中段。本次把两条链路接通到真底座。

## How to apply

**车辆外廓（主线 04b 多视角云端融合，不是端侧 TSDF）**：
- `VehicleContourScanViewModel`：8 角度引导采集 `CameraSource` 真帧 → 每方位抓 color+depth 存 `RgbdShot`（color `createScaledBitmap` 到 depth 分辨率，共用 depth 内参）→ 当方位点云预览=深度反投影 → 完成打 bundle 上传 → 等 `scan.fusion_done` → 下 GLB 回看。
- **bundle 契约真理源 = `server/fusion_service/rgbd_bundle.py`**：zip = `manifest.json`(session_key/frame_count/depth_unit_mm=1.0/intrinsics{w,h,fx,fy,cx,cy}/shots[]) + `rgb_i.png` + `depth_i.u16`(uint16 **小端** mm) + `conf_i.u8`(可选)。端侧 `core/data/.../scan/Scan3dBundleUploader` 逐字段复刻；`depth.data` bulk `get(ByteArray)` 是逐字节 memcpy（与 ByteOrder 无关），native 缓冲本就是 LE，verbatim 拷贝即对。`AssetUploadCompleteRequest` 加 `scan_session_id`/`frame_count`（complete 时带触发服务端融合入队）。
- **服务端补 `GET /v1/scans/{session_key}/result`**（`asset/handler.go:StreamFusionResult`）：融合 GLB 存 MinIO `scan_fusion/<session>/result.glb` 但**未登记为 asset**（无 asset_id 走不了 `/v1/assets/{id}/url`），且手机直连不到 MinIO 内网 → 用 server 流式中转 + owner 鉴权（`ScanFusionRepo.FindBySessionKey`）。**owner==nil 必须拒绝非特权用户**，不能 `owner!=nil && ...` 短路放行。
- `RealtimeEvent.ScanFusionDone` + parser `scan.fusion_done` 分支；`ScanFusionRepository`（core:data）把事件 + GLB 下载收口（feature 不直依赖 core:realtime/network）。GLB 回看 `GlbModelView`（filament-utils `ModelViewer` + gltfio + 一盏 SUN 光），`remember`+`LaunchedEffect(glbFile)`+`DisposableEffect{destroy()}`，重载前 `destroyModel()` 防 GPU 资源堆积。

**VIN（先通业务链路，native 拓印第二刀）**：
- `ScanCaptureScreen`/`VinCaptureViewModel`：拍单帧 RGB→JPEG → `CVEngineApi.vinPipeline(vehicle_model_id, tag=VMASK, image_binary)` → 真 verdict/识别 VIN/逐字符相似度。`vehicle_model_id` 本期默认 dev 值（待接 catalog 客户端）。
- **cvengine 是内网服务（:18810，worker 经 HMAC 调）**；端侧不该持 HMAC 密钥（会被逆向）。正解：**devserver 反代 `/cv/*`（JWT 保护）+ 服务端 `hmacauth.NewSigningTransport` 加签转发**，密钥只在服务端。`§14.1` 双轨鉴权由此满足。

**关键认知 / 雷点**：
- Kotlin **块注释会嵌套**：KDoc 文本里出现 `/*`（如写 `cv/ocr/*`）会开一个嵌套注释 → "Unclosed comment"。注释别写裸 `/*`。
- `EnvelopeErrorInterceptor` 对**所有**响应 `body.bytes()` 全缓冲再 peek 错误信封 → 流式大 GLB 会 OOM 且废 `@Streaming`。修：content-type 非 json/text/非 null 时直接放过不缓冲。
- devserver `auth.Required` **路径1 信任客户端传的 `X-Gomob-User-Id`**（"gateway 注入"模型，生产由 gateway 剥离客户端头）；devserver 直连无 gateway 时所有受保护端点都按此信任模型——是**既有平台行为**，新端点与之一致，非本次回归。

**验证**：双 ABI APK + `./dev.sh test` + `go test ./internal/asset` 全过；harness `scan_bundle_roundtrip`（独立复刻 Kotlin 字节布局→`unpack`+`fuse`）✅；5 维度对抗 review 19 confirmed 已逐条修。

**遗留（device-gated / 下一刀）**：① 真机出帧（M6.8b/M1.6 限制，2510DRK44C 直插可跑）；② **RGB↔depth 真配准**（现 approx resize，texture 受基线视差偏移，终态 M2 registration / `registeredToColor=true`）；③ VIN native 拓印图（RANSAC 正射重投影，M4.1）；④ VIN catalog 车型选择客户端。

关联：[[finding_multiview_rgbd_pivot_2026-05-07]]、[[finding_p100r3_mix_color_depth_2026-06-02]]。
