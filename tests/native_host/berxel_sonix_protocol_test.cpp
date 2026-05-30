// Host test — 验证 BerxelProtocolSonix 产生的字节序列跟 USB trace ground truth 完全一致
//
// 参考：.dev/m1.6.2-usb-trace/report.md 抓到的实际 control transfer 字节
//
// 编译：g++ -std=c++17 -I native/berxel/include native/berxel/src/gomob_berxel_protocol_sonix.cpp tests/native_host/berxel_sonix_protocol_test.cpp -o /tmp/berxel_sonix_test && /tmp/berxel_sonix_test

#include "gomob_berxel_protocol_sonix.h"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <vector>

struct Capture {
    uint8_t bmRequestType;
    uint8_t bRequest;
    uint16_t wValue;
    uint16_t wIndex;
    uint16_t wLength;
    std::vector<uint8_t> data;
};

// Mock libusb_control_transfer — 记录调用 + 返回长度（成功）
static std::vector<Capture>* g_captures = nullptr;
static std::vector<uint8_t> g_next_read_data; // GET_CUR mock 返回数据

static int mock_control_transfer(libusb_device_handle*,
                                 uint8_t bmRequestType, uint8_t bRequest,
                                 uint16_t wValue, uint16_t wIndex,
                                 uint8_t* data, uint16_t wLength,
                                 uint32_t /*timeout*/) {
    Capture c{bmRequestType, bRequest, wValue, wIndex, wLength, {}};
    if (data && wLength > 0) {
        const bool is_in = (bmRequestType & 0x80) != 0;
        if (is_in) {
            // GET_CUR — mock 把 g_next_read_data 写到 data
            for (uint16_t i = 0; i < wLength && i < g_next_read_data.size(); ++i) {
                data[i] = g_next_read_data[i];
            }
            c.data.assign(data, data + wLength);
        } else {
            // SET_CUR — 复制 caller 的数据到 capture
            c.data.assign(data, data + wLength);
        }
    }
    g_captures->push_back(c);
    return wLength;
}

#define ASSERT_EQ(a, b) do { \
    if ((a) != (b)) { \
        std::fprintf(stderr, "[FAIL] %s:%d: " #a " != " #b " (%lld vs %lld)\n", \
                     __FILE__, __LINE__, (long long)(a), (long long)(b)); \
        return false; \
    } \
} while(0)

static bool test_asic_write() {
    std::vector<Capture> caps;
    g_captures = &caps;

    // dummy non-null handle pointer for null check in implementation
    auto handle = reinterpret_cast<libusb_device_handle*>(0x1);
    gomob::berxel::BerxelProtocolSonix proto(handle, mock_control_transfer);

    // reg 0x10D8 写 0x42（M1.6.1 反编译看到的真实操作）
    int rc = proto.asic_write(0x10D8, 0x42);
    ASSERT_EQ(rc, 4);
    ASSERT_EQ(caps.size(), 1u);

    const auto& c = caps[0];
    ASSERT_EQ(c.bmRequestType, 0x21);  // OUT | Class | Interface
    ASSERT_EQ(c.bRequest, 0x01);       // SET_CUR
    ASSERT_EQ(c.wValue, 0x0100);       // selector 0x01
    ASSERT_EQ(c.wIndex, 0x0300);       // unit 3 在 Interface 0
    ASSERT_EQ(c.wLength, 4);
    ASSERT_EQ(c.data.size(), 4u);
    ASSERT_EQ(c.data[0], 0xD8);        // reg low byte
    ASSERT_EQ(c.data[1], 0x10);        // reg high byte
    ASSERT_EQ(c.data[2], 0x42);        // value
    ASSERT_EQ(c.data[3], 0x00);        // padding
    std::puts("[PASS] test_asic_write");
    return true;
}

static bool test_asic_read() {
    std::vector<Capture> caps;
    g_captures = &caps;
    g_next_read_data = {0xD9, 0x10, 0x99, 0x00}; // reg 0x10D9 返回值 0x99

    auto handle = reinterpret_cast<libusb_device_handle*>(0x1);
    gomob::berxel::BerxelProtocolSonix proto(handle, mock_control_transfer);

    int v = proto.asic_read(0x10D9);
    ASSERT_EQ(v, 0x99);                // returned value in slot 2
    ASSERT_EQ(caps.size(), 2u);        // SET_CUR 写 reg_addr + GET_CUR 读回

    ASSERT_EQ(caps[0].bmRequestType, 0x21);  // 第 1 次：SET_CUR 写 reg_addr
    ASSERT_EQ(caps[0].bRequest, 0x01);
    ASSERT_EQ(caps[0].wValue, 0x0100);
    ASSERT_EQ(caps[0].wIndex, 0x0300);
    ASSERT_EQ(caps[0].data[0], 0xD9);
    ASSERT_EQ(caps[0].data[1], 0x10);

    ASSERT_EQ(caps[1].bmRequestType, 0xa1);  // 第 2 次：GET_CUR 读
    ASSERT_EQ(caps[1].bRequest, 0x81);
    ASSERT_EQ(caps[1].wValue, 0x0100);
    ASSERT_EQ(caps[1].wIndex, 0x0300);
    ASSERT_EQ(caps[1].wLength, 4);
    std::puts("[PASS] test_asic_read");
    return true;
}

static bool test_batch_cmd_selector_19() {
    // 对照 .dev/m1.6.2-usb-trace 抓到的 selector 0x19 / wLength 512 / wIndex 0x0300 / unit 3
    std::vector<Capture> caps;
    g_captures = &caps;
    auto handle = reinterpret_cast<libusb_device_handle*>(0x1);
    gomob::berxel::BerxelProtocolSonix proto(handle, mock_control_transfer);

    std::vector<uint8_t> payload(512);
    for (size_t i = 0; i < payload.size(); ++i) payload[i] = static_cast<uint8_t>(i & 0xff);

    int rc = proto.batch_cmd(0x19, payload.data(), 512);
    ASSERT_EQ(rc, 512);
    ASSERT_EQ(caps.size(), 1u);
    ASSERT_EQ(caps[0].bmRequestType, 0x21);
    ASSERT_EQ(caps[0].bRequest, 0x01);
    ASSERT_EQ(caps[0].wValue, 0x1900);    // selector 0x19
    ASSERT_EQ(caps[0].wIndex, 0x0300);    // unit 3
    ASSERT_EQ(caps[0].wLength, 512);
    ASSERT_EQ(caps[0].data.size(), 512u);
    ASSERT_EQ(caps[0].data[0], 0);
    ASSERT_EQ(caps[0].data[511], 0xff);
    std::puts("[PASS] test_batch_cmd_selector_19");
    return true;
}

static bool test_batch_cmd_selector_1e_4096() {
    // 对照 USB trace 抓到的 selector 0x1e / wLength 4096
    std::vector<Capture> caps;
    g_captures = &caps;
    auto handle = reinterpret_cast<libusb_device_handle*>(0x1);
    gomob::berxel::BerxelProtocolSonix proto(handle, mock_control_transfer);

    std::vector<uint8_t> payload(4096, 0xab);
    int rc = proto.batch_cmd(0x1e, payload.data(), 4096);
    ASSERT_EQ(rc, 4096);
    ASSERT_EQ(caps.size(), 1u);
    ASSERT_EQ(caps[0].wValue, 0x1e00);
    ASSERT_EQ(caps[0].wIndex, 0x0300);
    ASSERT_EQ(caps[0].wLength, 4096);
    std::puts("[PASS] test_batch_cmd_selector_1e_4096");
    return true;
}

static bool test_xu_get_cur() {
    std::vector<Capture> caps;
    g_captures = &caps;
    g_next_read_data = {0xde, 0xad, 0xbe, 0xef};
    auto handle = reinterpret_cast<libusb_device_handle*>(0x1);
    gomob::berxel::BerxelProtocolSonix proto(handle, mock_control_transfer);

    uint8_t out[4] = {};
    int rc = proto.xu_get_cur(0x19, out, 4);
    ASSERT_EQ(rc, 4);
    ASSERT_EQ(caps[0].bmRequestType, 0xa1);
    ASSERT_EQ(caps[0].bRequest, 0x81);
    ASSERT_EQ(caps[0].wValue, 0x1900);
    ASSERT_EQ(caps[0].wIndex, 0x0300);
    ASSERT_EQ(out[0], 0xde);
    ASSERT_EQ(out[3], 0xef);
    std::puts("[PASS] test_xu_get_cur");
    return true;
}

static bool test_null_handle_safety() {
    auto proto = gomob::berxel::BerxelProtocolSonix(nullptr, mock_control_transfer);
    uint8_t buf[4] = {};
    ASSERT_EQ(proto.xu_set_cur(0x19, buf, 4), -1);

    auto proto2 = gomob::berxel::BerxelProtocolSonix(
        reinterpret_cast<libusb_device_handle*>(0x1), nullptr);
    ASSERT_EQ(proto2.xu_set_cur(0x19, buf, 4), -1);
    std::puts("[PASS] test_null_handle_safety");
    return true;
}

int main() {
    bool ok = true;
    ok &= test_asic_write();
    ok &= test_asic_read();
    ok &= test_batch_cmd_selector_19();
    ok &= test_batch_cmd_selector_1e_4096();
    ok &= test_xu_get_cur();
    ok &= test_null_handle_safety();
    if (!ok) {
        std::puts("\n[FAIL] some tests failed");
        return 1;
    }
    std::puts("\n[ALL PASS] 6/6 tests");
    return 0;
}
