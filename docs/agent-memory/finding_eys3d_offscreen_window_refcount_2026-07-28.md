# eYs3D 离屏窗必须 acquire 补偿厂商多减的强引用（2026-07-28）

**Why**：`Eys3dVendorCppSession` 用 `AImageReader` 造离屏 `ANativeWindow`，只为满足厂商
`startPreview` 的非空窗门控（见 [[finding_eys3d_android_bringup_0bytes_2026-06-09]]）。
窗口经 `cam_set_preview_display` 交给厂商后，**厂商 `cam_dtor` 会对它多减一次强引用**。
于是 `Teardown()` 里 `cam_dtor` 之后再 `AImageReader_delete`，reader 内部引用已被减到 0，
必崩在 `~AImageReader → RefBase::decStrong → __aarch64_ldadd4_rel`（SIGSEGV @ 0x0，
线程 `DefaultDispatch`）。LOG-AN10 / Android 15 上 100% 复现：VIN 拍摄全链路成功、
结果已回，随后释放相机时闪退。

**How to apply**：

- `MakeOffscreenWindow` 拿到 window 后必须 `ANativeWindow_acquire(win)`，**且 `Teardown` 里
  绝对不能配对 release**。那次 acquire 不是"我方持有一份引用"，而是**专门用来抵消厂商
  多减的那一次**；补一次 release 就把抵消作废，计数照样归零，崩得与未修时一模一样
  （实测：原始版崩在 `Teardown()+440`，加了 release 的版本崩在 `+520`，同一个栈）。
- 判断这类问题看引用计数账，别看代码对称性：`acquire`/`release` 成对是常规直觉，
  但在"对端实现有计数 bug"的场景里，单边 acquire 才是正解。
- `cam_set_preview_display(cam, nullptr, idx)` 解绑（ABI 头文件注释里写明支持）**挡不住**
  厂商减引用 —— 带着它照样崩。留着无害（最坏 no-op；万一某机型生效则变成一次窗口对象
  泄漏，远好过崩溃），但不要指望它单独解决问题。
- 验收口径：手机 logcat 出现 `Teardown 完成 cbFrames=N` 且无 `signal 11` 即通过。

**代码**：`native/eys3d/android/eys3d_vendor_cpp_session.cpp`
（`MakeOffscreenWindow` 的 acquire、`Teardown` 的解绑与 reader 删除）。
