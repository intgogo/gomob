#include "eys3d/android/eys3d_libuvc.h"

#include <android/log.h>
#include <dlfcn.h>

namespace gomob::eys3d::android {

namespace {
void* g_libuvc = nullptr;
}

bool LoadLibuvc(LibuvcApi* out) {
  if (!out) return false;
  // RTLD_GLOBAL：libuvc.so 的 NEEDED libusb100.so/libjpeg-turbo1500.so 一并加载；libuvc 内部全程用自带 libusb100。
  if (!g_libuvc) g_libuvc = dlopen("libuvc.so", RTLD_NOW | RTLD_GLOBAL);
  if (!g_libuvc) {
    __android_log_print(ANDROID_LOG_ERROR, "eys3d_stream", "dlopen libuvc.so failed: %s", dlerror());
    return false;
  }
#define UVC_SYM(field, name) out->field = reinterpret_cast<decltype(out->field)>(dlsym(g_libuvc, name))
  UVC_SYM(init2, "uvc_init2");
  UVC_SYM(exit, "uvc_exit");
  UVC_SYM(get_device_with_fd, "uvc_get_device_with_fd");
  UVC_SYM(open, "uvc_open");
  UVC_SYM(close, "uvc_close");
  UVC_SYM(get_libusb_handle, "uvc_get_libusb_handle");
  UVC_SYM(get_stream_ctrl_format_size, "uvc_get_stream_ctrl_format_size");
  UVC_SYM(start_streaming, "uvc_start_streaming");
  UVC_SYM(stop_streaming, "uvc_stop_streaming");
  UVC_SYM(unref_device, "uvc_unref_device");
  UVC_SYM(stream_open_ctrl, "uvc_stream_open_ctrl");
  UVC_SYM(stream_start, "uvc_stream_start");
  UVC_SYM(stream_stop, "uvc_stream_stop");
  UVC_SYM(stream_close, "uvc_stream_close");
  UVC_SYM(start_handler_thread, "uvc_start_handler_thread");
#undef UVC_SYM
  const bool ok = out->init2 && out->exit && out->get_device_with_fd && out->open && out->close &&
                  out->get_stream_ctrl_format_size && out->start_streaming && out->stop_streaming;
  __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "libuvc loaded, api ok=%d", ok);
  return ok;
}

}  // namespace gomob::eys3d::android
