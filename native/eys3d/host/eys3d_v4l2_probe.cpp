// 诊断工具（非产品代码）：用系统标准 uvcvideo / V4L2 测 RS-D550 能否出流。
// 目的——分水岭判定：若 uvcvideo 能出流，则我们自研 libusb probe 取流失败是流程 bug；
// 若 uvcvideo 也出不了，则确认 RS-D550 默认不推流、必须先发 Etron 专有 XU 激活序列。
// 用法：eys3d_v4l2_probe /dev/videoN [w] [h]

#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>

static int xioctl(int fd, unsigned long req, void* arg) {
    int r;
    do { r = ioctl(fd, req, arg); } while (r == -1 && errno == EINTR);
    return r;
}

int main(int argc, char** argv) {
    const char* dev = argc > 1 ? argv[1] : "/dev/video2";
    const int w = argc > 2 ? atoi(argv[2]) : 640;
    const int h = argc > 3 ? atoi(argv[3]) : 480;

    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) { printf("open %s 失败: %s\n", dev, strerror(errno)); return 1; }

    v4l2_capability cap{};
    if (xioctl(fd, VIDIOC_QUERYCAP, &cap) == 0) {
        printf("%s  driver=%s card=%s caps=0x%08x dev_caps=0x%08x %s\n", dev, cap.driver, cap.card,
               cap.capabilities, cap.device_caps,
               (cap.device_caps & V4L2_CAP_VIDEO_CAPTURE) ? "[CAPTURE]" : "[非capture]");
    }

    printf("支持格式:\n");
    for (int i = 0;; ++i) {
        v4l2_fmtdesc fd2{};
        fd2.index = i;
        fd2.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        if (xioctl(fd, VIDIOC_ENUM_FMT, &fd2) != 0) break;
        const uint32_t f = fd2.pixelformat;
        printf("  [%d] %c%c%c%c  %s\n", i, f & 0xff, (f >> 8) & 0xff, (f >> 16) & 0xff,
               (f >> 24) & 0xff, fd2.description);
    }

    v4l2_format fmt{};
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = w;
    fmt.fmt.pix.height = h;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;
    if (xioctl(fd, VIDIOC_S_FMT, &fmt) != 0) {
        printf("S_FMT 失败: %s\n", strerror(errno));
        close(fd);
        return 2;
    }
    const uint32_t got = fmt.fmt.pix.pixelformat;
    printf("协商格式: %dx%d %c%c%c%c sizeimage=%u\n", fmt.fmt.pix.width, fmt.fmt.pix.height,
           got & 0xff, (got >> 8) & 0xff, (got >> 16) & 0xff, (got >> 24) & 0xff,
           fmt.fmt.pix.sizeimage);

    v4l2_requestbuffers req{};
    req.count = 4;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &req) != 0) {
        printf("REQBUFS 失败: %s\n", strerror(errno));
        close(fd);
        return 3;
    }

    struct Buf { void* start; size_t len; } bufs[8];
    for (unsigned i = 0; i < req.count; ++i) {
        v4l2_buffer b{};
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        b.memory = V4L2_MEMORY_MMAP;
        b.index = i;
        if (xioctl(fd, VIDIOC_QUERYBUF, &b) != 0) { printf("QUERYBUF 失败\n"); return 4; }
        bufs[i].len = b.length;
        bufs[i].start = mmap(nullptr, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, b.m.offset);
        xioctl(fd, VIDIOC_QBUF, &b);
    }

    int type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_STREAMON, &type) != 0) {
        printf("STREAMON 失败: %s\n", strerror(errno));
        close(fd);
        return 5;
    }
    printf("STREAMON OK，等帧（最多 3s）...\n");

    int got_frames = 0;
    for (int t = 0; t < 30; ++t) {
        pollfd p{fd, POLLIN, 0};
        const int pr = poll(&p, 1, 100);
        if (pr <= 0) continue;
        v4l2_buffer b{};
        b.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        b.memory = V4L2_MEMORY_MMAP;
        if (xioctl(fd, VIDIOC_DQBUF, &b) != 0) continue;
        ++got_frames;
        const uint8_t* d = (const uint8_t*)bufs[b.index].start;
        printf("  ✅ 帧#%d bytesused=%u head=%02x %02x %02x %02x %02x %02x %02x %02x\n", got_frames,
               b.bytesused, d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]);
        xioctl(fd, VIDIOC_QBUF, &b);
        if (got_frames >= 3) break;
    }
    xioctl(fd, VIDIOC_STREAMOFF, &type);
    close(fd);
    printf("== uvcvideo 出流: %s（%d 帧）==\n", got_frames > 0 ? "成功" : "失败", got_frames);
    return got_frames > 0 ? 0 : 6;
}
