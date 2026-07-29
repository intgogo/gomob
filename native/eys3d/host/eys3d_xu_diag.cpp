// Etron XU 读写诊断（M6.3）：写 FW 寄存器 0xF0(videoMode) 再读回，确认激活寄存器写进去没。
// 据 libESPDI.so 反汇编：FW 写 payload={0x20,addr,val,0x00}；FW 读 payload={0xA0,addr,0,0} 再 GET_CUR 取 buf[1]。
// 全经 XU id=4/iface0：wValue=0x0300 wIndex=0x0400 wLen=4。
#include <libusb-1.0/libusb.h>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <thread>

static constexpr uint16_t kVid = 0x3438, kPid = 0x0206;

static int ctrl(libusb_device_handle* h, uint8_t bm, uint8_t br, uint16_t wv, uint16_t wi,
                uint8_t* d, uint16_t l) {
    return libusb_control_transfer(h, bm, br, wv, wi, d, l, 2000);
}
static int fw_write(libusb_device_handle* h, uint8_t addr, uint8_t val) {
    uint8_t d[4] = {0x20, addr, val, 0x00};
    return ctrl(h, 0x21, 0x01, 0x0300, 0x0400, d, 4);  // SET_CUR
}
// FW 读：先 SET_CUR 下 read 命令(opcode 0xA0)，再 GET_CUR 取回 4 字节，值在 buf[1]
static int fw_read(libusb_device_handle* h, uint8_t addr, uint8_t* out, uint8_t* raw4) {
    uint8_t cmd[4] = {0xA0, addr, 0x00, 0x00};
    int r = ctrl(h, 0x21, 0x01, 0x0300, 0x0400, cmd, 4);
    if (r < 0) return r;
    uint8_t b[4] = {0, 0, 0, 0};
    r = ctrl(h, 0xA1, 0x81, 0x0300, 0x0400, b, 4);  // GET_CUR
    if (r >= 0) {
        if (out) *out = b[1];
        if (raw4) memcpy(raw4, b, 4);
    }
    return r;
}

int main() {
    if (libusb_init(nullptr) != 0) { printf("libusb_init 失败\n"); return 1; }
    libusb_device_handle* h = libusb_open_device_with_vid_pid(nullptr, kVid, kPid);
    if (!h) { printf("打开 %04x:%04x 失败（在线？）\n", kVid, kPid); libusb_exit(nullptr); return 2; }
    libusb_set_auto_detach_kernel_driver(h, 1);
    int cr = libusb_claim_interface(h, 0);
    printf("claim IF0 rc=%d %s\n", cr, cr ? libusb_error_name(cr) : "OK");

    printf("\n== 写 0xF0(videoMode) 再读回，验证寄存器是否真生效 ==\n");
    for (uint8_t mode : {(uint8_t)26, (uint8_t)28, (uint8_t)32, (uint8_t)29}) {
        int wr = fw_write(h, 0xF0, mode);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        uint8_t rb = 0xff;
        uint8_t raw[4] = {0};
        int rr = fw_read(h, 0xF0, &rb, raw);
        printf("  写 0xF0=%-3u → SET rc=%d ; 读回=%-3u (GET rc=%d, raw=%02x %02x %02x %02x) %s\n",
               mode, wr, rb, rr, raw[0], raw[1], raw[2], raw[3],
               (rr >= 0 && rb == mode) ? "✅写进去了" : "⚠️没写进/不回读");
    }

    printf("\n== 探几个 FW/HW 寄存器读，看设备对 XU 读的响应 ==\n");
    for (uint8_t addr : {(uint8_t)0xF0, (uint8_t)0xED, (uint8_t)0xE0, (uint8_t)0xF4, (uint8_t)0x00}) {
        uint8_t rb = 0xff;
        uint8_t raw[4] = {0};
        int rr = fw_read(h, addr, &rb, raw);
        printf("  读 0x%02x → rc=%d val=%u raw=%02x %02x %02x %02x\n", addr, rr, rb, raw[0], raw[1],
               raw[2], raw[3]);
    }

    libusb_release_interface(h, 0);
    libusb_close(h);
    libusb_exit(nullptr);
    return 0;
}
