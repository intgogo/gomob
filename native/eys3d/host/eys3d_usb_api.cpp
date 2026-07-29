#include "eys3d/host/eys3d_usb_api.h"

namespace gomob::eys3d {

UsbApi& Eys3dUsb() {
  static UsbApi api;
  return api;
}

void Eys3dUsbSetHostApi() {
  UsbApi& a = Eys3dUsb();
  a.init = &libusb_init;
  a.exit = &libusb_exit;
  a.get_device_with_fd = nullptr;  // host 不用
  a.wrap_sys_device = &libusb_wrap_sys_device;
  a.open = &libusb_open;
  a.close = &libusb_close;
  a.claim_interface = &libusb_claim_interface;
  a.release_interface = &libusb_release_interface;
  a.control_transfer = &libusb_control_transfer;
  a.alloc_transfer = &libusb_alloc_transfer;
  a.submit_transfer = &libusb_submit_transfer;
  a.cancel_transfer = &libusb_cancel_transfer;
  a.free_transfer = &libusb_free_transfer;
  a.handle_events_timeout = &libusb_handle_events_timeout;
  a.handle_events = &libusb_handle_events;
  a.handle_events_completed = &libusb_handle_events_completed;
  a.error_name = &libusb_error_name;
  a.clear_halt = &libusb_clear_halt;
  a.reset_device = &libusb_reset_device;
  a.set_auto_detach_kernel_driver = &libusb_set_auto_detach_kernel_driver;
  a.kernel_driver_active = &libusb_kernel_driver_active;
  a.detach_kernel_driver = &libusb_detach_kernel_driver;
  a.bulk_transfer = &libusb_bulk_transfer;
}

}  // namespace gomob::eys3d
