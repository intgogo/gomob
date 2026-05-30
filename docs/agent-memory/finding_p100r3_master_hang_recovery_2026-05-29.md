# P100R3 master 被 color 流挂死后只能物理断电恢复（2026-05-29）

跑 host demo 的 **color MJPEG 流**会把 master Novatek 芯片(`0603`)挂死:掉出 USB 枚举,
`dmesg` 刷 `device not accepting address / error -71 / -110`,companion(`3558`)不受影响。

## Why 软件救不回来

- master 挂在 **Terminus hub `1a40:0101`** 下游;该 hub 是 **自供电(Self Powered, bmAttributes bit6=1)
  + Ganged power switching(`wHubCharacteristic 0x0000`,无按端口断电)**。
- 固件挂死**只能靠断 VBUS 复位**。但:uhubctl 无 PPPS 控不了它;内核自带 `attempt power cycle` 无效;
  连**最强杠杆 xHCI 控制器 unbind/bind**(master 与 companion 同挂 NVIDIA TU102 `0000:0a:00.2`)
  也无效 —— 上游断电时自供电 hub 靠自己电源继续给 master 供电,master 从没掉过电。
- 结论:**host 侧任何软件手段都够不到 master 的 VBUS**,这是自供电+联动 hub 的物理死局。

## ⚠ depth-only 也会挂 master:keepalive 坏了(2026-05-29 补)

2026-05-29 实测:depth-only 模式跑 ~20min 后 master 照样挂。真因=**XU5 keepalive 的 `set_cur` 一直
超时**(`rc=-7 LIBUSB_ERROR_TIMEOUT`,~550ms 一次全失败,errs 累计 400+,ok 始终=1)。firmware 需要
50ms 心跳(记忆 [[finding_p100r3_bulk_no_data_2026-05-27]]),心跳没送达 → master 饿死。depth 流靠 companion
照常推(master 一旦开流似乎可短暂脱离),所以表面"在跑",实则 master 在慢性饿死。
→ **这是"USB 经常挂"的真凶之一**(另一个是 color 流秒挂)。修 keepalive(查 set_cur 为何 timeout:
endpoint/时序/wValue)是止血关键,需 master 物理恢复后真机调。

## How to apply

- 别再花时间试软件复位 master。恢复=**物理断一次电**,最省事是**拔 hub 自己的电源砖 5 秒**(联动断电连 master 一起重启),或拔相机 USB-C。
- 预防:demo/任何 host 工具默认 **depth-only**(master 只跑 XU5 keepalive,不开 color 视频流);
  `vin_rectify_gui` 已设 `BerxelStreamConfig.depth_only=true` 默认 + 复选框,见其注释。
- 生产 BOM 本就要带电 hub(供电),但要选**支持 PPPS 的 hub**,否则远程运维同样无法软复位。
- 深度质量效果可不依赖硬件展示:`.dev/live-validate/render_showcase.py` 用现采真帧离线复算出图。
- 关联 [[finding_powered_hub_unblocks_vivo_dual_stream_2026-05-27]](带电 hub 救活双流)、
  [[finding_p100r3_depth_ir_interleaved_2026-05-29]](color/depth 分流)。
