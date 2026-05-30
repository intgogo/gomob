#pragma once

#include <QImage>
#include <QThread>

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace gomob::berxel::host {
class UsbContext;
class UsbDevice;
struct XuPayload;
}

struct BerxelVideoMode {
    int frame_index = 0;
    int width = 0;
    int height = 0;
    int fps = 0;
    uint32_t interval_100ns = 0;
};

struct BerxelStreamConfig {
    BerxelVideoMode color;
    BerxelVideoMode depth;
    bool depth_as_light_ir = false;
    // 仅 DEPTH（不开 master color 视频流）：master 只跑轻量 XU5 keepalive。
    // color MJPEG 流会把 master Novatek 芯片挂死（掉枚举，error -71，需物理拔插）；
    // depth-only 用 host-probe 验证过的 keepalive 路径，安全 + 聚焦深度质量。
    bool depth_only = true;
};

class BerxelStreamWorker : public QThread
{
    Q_OBJECT

public:
    explicit BerxelStreamWorker(const BerxelStreamConfig& config, QObject* parent = nullptr);
    ~BerxelStreamWorker() override;

    void requestStop();

signals:
    void logMessage(const QString& message);
    void statusChanged(const QString& status);
    void colorFrameReady(const QImage& image, quint64 frameIndex, quint64 bytes);
    void depthFrameReady(const QImage& image, quint64 frameIndex, quint64 bytes);
    // 时域降噪 + 飞点剔除后的深度图（飞点高亮品红）+ live 质量统计。
    void depthProcessedFrameReady(const QImage& image, quint64 frameIndex,
                                  double noiseFloorMm, double flyingPct, double densityPct);
    void streamFailed(const QString& message);
    void streamStopped();

protected:
    void run() override;

private:
    using UsbDevice = gomob::berxel::host::UsbDevice;
    using UsbContext = gomob::berxel::host::UsbContext;
    using XuPayload = gomob::berxel::host::XuPayload;

    void emitLog(const QString& message);
    bool setupDevices(UsbContext& ctx,
                      std::unique_ptr<UsbDevice>* master,
                      std::unique_ptr<UsbDevice>* companion);
    void colorLoop(UsbDevice& master);
    void depthLoop(UsbDevice& companion);
    void sendMasterStop(UsbDevice& master);
    void sendCompanionStop(UsbDevice& companion);

    static std::vector<unsigned char> jpegPayload(const unsigned char* data, int actual);
    static QImage depthToImage(const std::vector<unsigned char>& frame, const BerxelVideoMode& mode);
    // 融合后 active raw16 + 飞点 mask + 真置信 → 伪彩；飞点品红，低置信(不稳)出黑洞，无效出黑。
    static QImage depthProcessedToImage(const std::vector<uint16_t>& fusedActiveRaw,
                                        const std::vector<uint8_t>& flyingMask,
                                        const std::vector<uint8_t>& confidence,
                                        const BerxelVideoMode& mode);
    static QImage lightIrToImage(const std::vector<unsigned char>& frame, const BerxelVideoMode& mode);

    BerxelStreamConfig m_config;
    std::atomic_bool m_stopRequested;
};
