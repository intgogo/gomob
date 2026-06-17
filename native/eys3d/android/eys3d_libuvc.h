// vendor libuvc.so（saki4510t fork）最小重建头 + dlopen 函数表。
//
// 本仓 third_party/libuvc-android 是 pupil libuvc（无 fd 入口），与 VINCreator 的 saki4510t libuvc.so ABI 不兼容，
// 故按 saki4510t 重建本头的最小子集，配合 dlopen vendor libuvc.so 验证"用 libuvc 取流是否连续出帧"（libusb 后端已排除）。
//
// ⚠ ABI：uvc_context/device/device_handle 为不透明指针；uvc_stream_ctrl_t 只在 get→start 间透传（超额分配兜底）；
//   uvc_frame_t 回调只读前置字段（data/data_bytes/width/height/frame_format），按 saki4510t 标准布局。
#pragma once

#include <cstddef>
#include <cstdint>
#include <sys/time.h>

#include <libusb-1.0/libusb.h>  // libusb_device_handle*

namespace gomob::eys3d::android {

enum uvc_frame_format {
  UVC_FRAME_FORMAT_UNKNOWN = 0,
  UVC_FRAME_FORMAT_ANY = 0,
  UVC_FRAME_FORMAT_UNCOMPRESSED,
  UVC_FRAME_FORMAT_COMPRESSED,
  UVC_FRAME_FORMAT_YUYV,
  UVC_FRAME_FORMAT_UYVY,
  UVC_FRAME_FORMAT_RGB,
  UVC_FRAME_FORMAT_BGR,
  UVC_FRAME_FORMAT_MJPEG,
  UVC_FRAME_FORMAT_H264,
  UVC_FRAME_FORMAT_GRAY8,
  UVC_FRAME_FORMAT_GRAY16,
};

struct uvc_context;
struct uvc_device;
struct uvc_device_handle;
struct uvc_stream_handle;  // 多流句柄(每路 VS 接口一个)，不透明。

// saki4510t uvc_stream_ctrl_t 标准布局(AAPCS64 自然对齐，与 libuvc.so 编译器一致)。
// 既能让 get_stream_ctrl_format_size 回填，也能让我们读 bInterfaceNumber 区分 IF1(color)/IF2(depth)
// 并【手搓】depth 流 ctrl(用 proven 协商索引 fmt=1/frame=1)绕过格式-GUID 匹配。末尾 _reserved 超额兜底。
struct uvc_stream_ctrl {
  uint16_t bmHint;
  uint8_t bFormatIndex;
  uint8_t bFrameIndex;
  uint32_t dwFrameInterval;
  uint16_t wKeyFrameRate;
  uint16_t wPFrameRate;
  uint16_t wCompQuality;
  uint16_t wCompWindowSize;
  uint16_t wDelay;
  uint32_t dwMaxVideoFrameSize;
  uint32_t dwMaxPayloadTransferSize;
  uint32_t dwClockFrequency;
  uint8_t bmFramingInfo;
  uint8_t bPreferredVersion;
  uint8_t bMinVersion;
  uint8_t bMaxVersion;
  uint8_t bInterfaceNumber;
  uint8_t _reserved[64];  // libuvc 内部 sizeof 若更大，超额防越界读写
};

// saki4510t uvc_frame_t 前置字段（仅读这些）。
struct uvc_frame {
  void* data;
  size_t data_bytes;
  uint32_t width;
  uint32_t height;
  enum uvc_frame_format frame_format;
  size_t step;
  uint32_t sequence;
  struct timeval capture_time;
  struct uvc_device_handle* source;
  uint8_t library_owns_data;
  // 之后还有字段，不访问。
};

typedef void(uvc_frame_callback_t)(struct uvc_frame* frame, void* user_ptr);

// dlopen vendor libuvc.so 后 dlsym 填表。
struct LibuvcApi {
  int (*init2)(uvc_context**, libusb_context*, const char* usbfs) = nullptr;
  void (*exit)(uvc_context*) = nullptr;
  int (*get_device_with_fd)(uvc_context*, uvc_device**, int vid, int pid, const char* serial,
                            int fd, int busnum, int devaddr) = nullptr;
  int (*open)(uvc_device*, uvc_device_handle**) = nullptr;
  void (*close)(uvc_device_handle*) = nullptr;
  libusb_device_handle* (*get_libusb_handle)(uvc_device_handle*) = nullptr;
  int (*get_stream_ctrl_format_size)(uvc_device_handle*, uvc_stream_ctrl*, enum uvc_frame_format,
                                     int width, int height, int fps) = nullptr;
  int (*start_streaming)(uvc_device_handle*, uvc_stream_ctrl*, uvc_frame_callback_t*, void*,
                         uint8_t flags) = nullptr;
  void (*stop_streaming)(uvc_device_handle*) = nullptr;
  void (*unref_device)(uvc_device*) = nullptr;
  // 多流 API(depth 第二路用)：open_ctrl 从 ctrl 建流句柄，stream_start 起回调流。
  int (*stream_open_ctrl)(uvc_device_handle*, uvc_stream_handle**, uvc_stream_ctrl*) = nullptr;
  int (*stream_start)(uvc_stream_handle*, uvc_frame_callback_t*, void*, uint8_t flags) = nullptr;
  void (*stream_stop)(uvc_stream_handle*) = nullptr;
  void (*stream_close)(uvc_stream_handle*) = nullptr;
  // 不开 libuvc 流也跑起 libusb 事件线程(在 libuvc 的 context 上),供我们手动 bulk URB 回调被驱动。
  int (*start_handler_thread)(uvc_device_handle*) = nullptr;
};

// dlopen vendor libuvc.so（RTLD_GLOBAL：让其 NEEDED libusb100 符号可被复用）+ dlsym。返回是否齐全。
bool LoadLibuvc(LibuvcApi* out);

}  // namespace gomob::eys3d::android
