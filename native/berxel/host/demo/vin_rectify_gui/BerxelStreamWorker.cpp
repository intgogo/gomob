#include "BerxelStreamWorker.h"

#include "gomob_berxel_host_sdk.h"

#include <QByteArray>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <thread>

using gomob::berxel::host::BulkStats;
using gomob::berxel::host::P100R3VideoMode;
using gomob::berxel::host::UvcNegotiation;
using gomob::berxel::host::UvcStreamConfig;
using gomob::berxel::host::UsbContext;

namespace {

constexpr const char* kDepthMasterPayloads =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_xu5_init.json";
constexpr const char* kColorMasterPayloads =
    "native/berxel/host/assets/iHawkP100R3_color_master_xu5_init.json";
constexpr const char* kCompanionInit =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_init_sequence.json";
constexpr float kDepthPreviewMinMm = 200.0f;
constexpr float kDepthPreviewMaxMm = 2000.0f;
constexpr uint16_t kLightIrPreviewMin = 32;
constexpr uint16_t kLightIrPreviewMax = 512;

// companion 0x82 交织真深度帧(状态行首像素 0x0600)与 IR/phase 帧(0x0500)；只把真深度喂时域/渲染，
// 否则 IR 帧被当深度渲染成垃圾（预览精细/粗交替）。与 JNI is_real_depth_frame 同口径。
bool isRealDepthFrame(const std::vector<unsigned char>& f)
{
    if (f.size() < 2) return true;
    const uint16_t marker = static_cast<uint16_t>(f[0]) |
                            static_cast<uint16_t>(static_cast<uint16_t>(f[1]) << 8);
    return marker == 0x0600;
}

struct UvcPayloadView {
    int offset = 0;
    int length = 0;
    bool valid = false;
    bool eof = false;
    bool error = false;
    unsigned char fid = 0;
};

UvcPayloadView parseUvcPayload(const unsigned char* data, int actual)
{
    UvcPayloadView view;
    if (!data || actual <= 0) return view;

    view.length = actual;
    if (actual < 2) return view;

    const int headerLen = data[0];
    const unsigned char flags = data[1];
    const bool looksLikeUvc = headerLen >= 2 &&
                              headerLen <= actual &&
                              headerLen <= 64 &&
                              ((flags & 0x80) != 0);
    if (!looksLikeUvc) return view;

    view.offset = headerLen;
    view.length = actual - headerLen;
    view.valid = true;
    view.eof = (flags & 0x02) != 0;
    view.error = (flags & 0x40) != 0;
    view.fid = flags & 0x01;
    return view;
}

bool hasKeepalivePrefix(const gomob::berxel::host::XuPayload& payload)
{
    static constexpr unsigned char kPrefix[] = {
        0x42, 0x58, 0x0a, 0x00, 0x0d, 0x00, 0x00, 0x00,
    };
    return payload.data.size() >= sizeof(kPrefix) &&
           std::equal(kPrefix, kPrefix + sizeof(kPrefix), payload.data.begin());
}

bool findKeepaliveSeed(const std::vector<gomob::berxel::host::XuPayload>& payloads,
                       gomob::berxel::host::XuPayload* out)
{
    for (auto it = payloads.rbegin(); it != payloads.rend(); ++it) {
        if (!hasKeepalivePrefix(*it)) continue;
        if (out) *out = *it;
        return true;
    }
    return false;
}

P100R3VideoMode toSdkMode(const BerxelVideoMode& mode)
{
    return P100R3VideoMode{
        static_cast<uint8_t>(mode.frame_index),
        static_cast<uint16_t>(mode.width),
        static_cast<uint16_t>(mode.height),
        static_cast<uint16_t>(mode.fps),
        mode.interval_100ns,
    };
}

int findMarker(const QByteArray& bytes, unsigned char a, unsigned char b, int start)
{
    for (int i = std::max(0, start); i + 1 < bytes.size(); ++i) {
        if (static_cast<unsigned char>(bytes[i]) == a &&
            static_cast<unsigned char>(bytes[i + 1]) == b) {
            return i;
        }
    }
    return -1;
}

unsigned char clampByte(int value)
{
    return static_cast<unsigned char>(std::max(0, std::min(255, value)));
}

int depthFrameBytes(const BerxelVideoMode& mode)
{
    return mode.width * mode.height * 2;
}

BerxelVideoMode activeDepthMode(const BerxelVideoMode& mode)
{
    const P100R3VideoMode active = gomob::berxel::host::p100r3_depth_active_mode(toSdkMode(mode));
    return BerxelVideoMode{
        static_cast<int>(active.frame_index),
        static_cast<int>(active.width),
        static_cast<int>(active.height),
        static_cast<int>(active.fps),
        active.interval_100ns,
    };
}

}  // namespace

BerxelStreamWorker::BerxelStreamWorker(const BerxelStreamConfig& config, QObject* parent)
    : QThread(parent),
      m_config(config),
      m_stopRequested(false)
{
}

BerxelStreamWorker::~BerxelStreamWorker()
{
    requestStop();
    wait(3000);
}

void BerxelStreamWorker::requestStop()
{
    m_stopRequested.store(true);
}

void BerxelStreamWorker::emitLog(const QString& message)
{
    emit logMessage(message);
}

void BerxelStreamWorker::run()
{
    m_stopRequested.store(false);
    UsbContext ctx;
    std::unique_ptr<UsbDevice> master;
    std::unique_ptr<UsbDevice> companion;
    std::atomic_bool keepaliveRunning{true};
    BulkStats keepaliveStats;
    std::thread keepaliveThread;

    try {
        if (!ctx.ok()) {
            emit streamFailed(QStringLiteral("libusb 初始化失败"));
            emit streamStopped();
            return;
        }
        if (!setupDevices(ctx, &master, &companion)) {
            emit streamStopped();
            return;
        }

        const auto keepalivePayloads = gomob::berxel::host::load_xu_payloads(
            kDepthMasterPayloads,
            0x0100,
            gomob::berxel::host::kP100R3MasterXu5WIndex);
        XuPayload keepaliveSeed;
        if (findKeepaliveSeed(keepalivePayloads, &keepaliveSeed)) {
            keepaliveThread = std::thread(gomob::berxel::host::master_keepalive_loop,
                                          std::ref(*master),
                                          keepaliveSeed,
                                          50,
                                          std::ref(keepaliveRunning),
                                          std::ref(keepaliveStats),
                                          [this](const std::string& msg) {
                                              emitLog(QString::fromStdString(msg));
                                          });
        }

        emit statusChanged(m_config.depth_only ? QStringLiteral("DEPTH 运行中（时域降噪+飞点剔除）")
                                               : QStringLiteral("双流运行中"));
        std::thread colorThread;
        if (!m_config.depth_only) {
            colorThread = std::thread(&BerxelStreamWorker::colorLoop, this, std::ref(*master));
        }
        std::thread depthThread(&BerxelStreamWorker::depthLoop, this, std::ref(*companion));
        if (colorThread.joinable()) colorThread.join();
        if (depthThread.joinable()) depthThread.join();

        keepaliveRunning.store(false);
        if (keepaliveThread.joinable()) keepaliveThread.join();
        sendMasterStop(*master);
        if (m_config.depth_as_light_ir) {
            sendCompanionStop(*companion);
        }
        emit statusChanged(QStringLiteral("已停止"));
    } catch (const std::exception& e) {
        emit streamFailed(QString::fromUtf8(e.what()));
        keepaliveRunning.store(false);
        if (keepaliveThread.joinable()) keepaliveThread.join();
        if (master) {
            sendMasterStop(*master);
        }
        if (m_config.depth_as_light_ir && companion) {
            sendCompanionStop(*companion);
        }
    }

    emit streamStopped();
}

bool BerxelStreamWorker::setupDevices(UsbContext& ctx,
                                      std::unique_ptr<UsbDevice>* masterOut,
                                      std::unique_ptr<UsbDevice>* companionOut)
{
    emit statusChanged(QStringLiteral("初始化 master"));
    // depth-only 用 depth master init（仅 keepalive，不挂 master）；否则用 color master init（含 color 流）。
    const char* masterInit = m_config.depth_only ? kDepthMasterPayloads : kColorMasterPayloads;
    auto masterPayloads = gomob::berxel::host::load_xu_payloads(
        masterInit,
        0x0100,
        gomob::berxel::host::kP100R3MasterXu5WIndex,
        -1);
    if (masterPayloads.empty()) {
        emit streamFailed(QStringLiteral("master XU5 初始化序列加载失败"));
        return false;
    }
    const int patched = gomob::berxel::host::refresh_master_time_sync_payloads(&masterPayloads);
    emitLog(QStringLiteral("master time-sync refreshed: %1").arg(patched));
    if (m_config.depth_only) {
        emitLog(QStringLiteral("仅 DEPTH 模式：master 只跑 XU5 keepalive，不开 color 视频流（避免挂 master）"));
    } else {
        std::string colorOpenStreamHex;
        const int colorOpenStreamPatched =
            gomob::berxel::host::patch_p100r3_master_color_open_stream_payloads(
                &masterPayloads,
                toSdkMode(m_config.color),
                &colorOpenStreamHex);
        emitLog(QStringLiteral("master COLOR OpenStream patched: %1 -> %2x%3@%4")
                    .arg(colorOpenStreamPatched)
                    .arg(m_config.color.width)
                    .arg(m_config.color.height)
                    .arg(m_config.color.fps));
        emitLog(QStringLiteral("master COLOR OpenStream payload: %1")
                    .arg(QString::fromStdString(colorOpenStreamHex)));
    }

    std::unique_ptr<UsbDevice> master = ctx.open(gomob::berxel::host::kP100R3MasterId);
    if (!master) {
        emit streamFailed(QStringLiteral("master 0603:001f 未发现或无法打开"));
        return false;
    }
    if (!master->claim_interface(0, [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); }) ||
        !master->claim_interface(1, [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); })) {
        emit streamFailed(QStringLiteral("master interface claim 失败"));
        return false;
    }
    if (!gomob::berxel::host::replay_xu_payloads(
            *master,
            masterPayloads,
            true,
            "master",
            [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); })) {
        emit streamFailed(QStringLiteral("master XU5 replay 失败"));
        return false;
    }
    if (m_config.depth_as_light_ir) {
        const uint8_t fps = static_cast<uint8_t>(
            std::clamp<int>(m_config.depth.fps, 1, 255));
        const XuPayload payload =
            gomob::berxel::host::make_p100r3_master_force_internal_pwm_trigger_payload(true, fps);
        emitLog(QStringLiteral("master force internal PWM trigger enabled, fps=%1").arg(fps));
        if (!gomob::berxel::host::replay_xu_payloads(
                *master,
                {payload},
                true,
                "master-light-ir-pwm",
                [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); })) {
            emit streamFailed(QStringLiteral("master light-ir PWM trigger 失败"));
            return false;
        }
    }

    emit statusChanged(QStringLiteral("初始化 companion"));
    auto companionPayloads = gomob::berxel::host::load_xu_payloads(
        kCompanionInit,
        0,
        gomob::berxel::host::kP100R3CompanionXu3WIndex);
    if (companionPayloads.empty()) {
        emit streamFailed(QStringLiteral("companion XU3 初始化序列加载失败"));
        return false;
    }
    std::string depthOpenStreamHex;
    const int depthOpenStreamPatched = m_config.depth_as_light_ir
        ? gomob::berxel::host::patch_p100r3_companion_light_ir_open_stream_payloads(
            &companionPayloads,
            toSdkMode(m_config.depth),
            &depthOpenStreamHex)
        : gomob::berxel::host::patch_p100r3_companion_depth_open_stream_payloads(
            &companionPayloads,
            toSdkMode(m_config.depth),
            &depthOpenStreamHex);
    const QString depthLabel = m_config.depth_as_light_ir
        ? QStringLiteral("LIGHT_IR")
        : QStringLiteral("DEPTH");
    const uint8_t streamModeCode = m_config.depth_as_light_ir
        ? 0x02
        : gomob::berxel::host::p100r3_depth_mode_code(toSdkMode(m_config.depth));
    emitLog(QStringLiteral("companion %1 OpenStream patched: %2 -> %3x%4@%5 code=0x%6")
                .arg(depthLabel)
                .arg(depthOpenStreamPatched)
                .arg(m_config.depth.width)
                .arg(m_config.depth.height)
                .arg(m_config.depth.fps)
                .arg(streamModeCode,
                     2,
                     16,
                     QChar('0')));
    emitLog(QStringLiteral("companion %1 OpenStream payload prefix: %2")
                .arg(depthLabel)
                .arg(QString::fromStdString(depthOpenStreamHex)));

    std::unique_ptr<UsbDevice> companion = ctx.open(gomob::berxel::host::kP100R3CompanionId);
    if (!companion) {
        emit streamFailed(QStringLiteral("companion 3558:1012 未发现或无法打开"));
        return false;
    }
    if (!companion->claim_interface(0, [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); }) ||
        !companion->claim_interface(1, [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); })) {
        emit streamFailed(QStringLiteral("companion interface claim 失败"));
        return false;
    }
    if (!gomob::berxel::host::replay_xu_payloads(
            *companion,
            companionPayloads,
            true,
            "companion",
            [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); })) {
        emit streamFailed(QStringLiteral("companion XU3 replay 失败"));
        return false;
    }
    gomob::berxel::host::P100R3DepthControls depthControls;
    depthControls.enabled = true;
    depthControls.auto_exposure = true;
    depthControls.confidence = 3;
    depthControls.set_temporal_denoise = true;
    depthControls.temporal_denoise = false;
    depthControls.set_spatial_denoise = true;
    depthControls.spatial_denoise = false;
    if (!m_config.depth_as_light_ir &&
        !gomob::berxel::host::apply_p100r3_depth_controls(
            *companion,
            depthControls,
            [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); })) {
        emit streamFailed(QStringLiteral("companion depth controls apply 失败"));
        return false;
    }

    UvcStreamConfig depthConfig;
    depthConfig.name = m_config.depth_as_light_ir ? "companion-light-ir" : "companion-depth";
    depthConfig.vs_interface = 1;
    depthConfig.endpoint = 0x82;
    depthConfig.format_index = 1;
    depthConfig.frame_index = static_cast<uint8_t>(m_config.depth.frame_index);
    depthConfig.frame_interval_100ns = m_config.depth.interval_100ns;
    UvcNegotiation depthNegotiation;
    if (!gomob::berxel::host::negotiate_uvc_stream(
            *companion,
            depthConfig,
            &depthNegotiation,
            [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); })) {
        emit streamFailed(QStringLiteral("depth UVC commit 失败"));
        return false;
    }

    UvcStreamConfig colorConfig;
    colorConfig.name = "master-color";
    colorConfig.vs_interface = 1;
    colorConfig.endpoint = 0x81;
    colorConfig.format_index = 1;
    colorConfig.frame_index = static_cast<uint8_t>(m_config.color.frame_index);
    colorConfig.frame_interval_100ns = m_config.color.interval_100ns;
    UvcNegotiation colorNegotiation;
    if (!gomob::berxel::host::negotiate_uvc_stream(
            *master,
            colorConfig,
            &colorNegotiation,
            [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); })) {
        emit streamFailed(QStringLiteral("color UVC commit 失败"));
        return false;
    }

    *masterOut = std::move(master);
    *companionOut = std::move(companion);
    return true;
}

void BerxelStreamWorker::colorLoop(UsbDevice& master)
{
    std::vector<unsigned char> buffer(16384);
    QByteArray streamBuffer;
    streamBuffer.reserve(std::max(2 * 1024 * 1024, m_config.color.width * m_config.color.height));
    quint64 bytes = 0;
    quint64 frames = 0;
    bool firstFrameLogged = false;
    auto lastEmit = std::chrono::steady_clock::now() - std::chrono::milliseconds(100);

    while (!m_stopRequested.load()) {
        int actual = 0;
        const int rc = master.bulk_in(0x81, buffer.data(), static_cast<int>(buffer.size()), &actual, 200);
        if (rc != 0 || actual <= 0) {
            continue;
        }
        bytes += static_cast<quint64>(actual);
        const std::vector<unsigned char> payload = jpegPayload(buffer.data(), actual);
        if (!payload.empty()) {
            streamBuffer.append(reinterpret_cast<const char*>(payload.data()), static_cast<int>(payload.size()));
        }

        while (true) {
            const int soi = findMarker(streamBuffer, 0xff, 0xd8, 0);
            if (soi < 0) {
                if (streamBuffer.size() > 64 * 1024) {
                    streamBuffer = streamBuffer.right(1);
                }
                break;
            }
            const int eoi = findMarker(streamBuffer, 0xff, 0xd9, soi + 2);
            if (eoi < 0) {
                if (soi > 0) {
                    streamBuffer.remove(0, soi);
                }
                if (streamBuffer.size() > 8 * 1024 * 1024) {
                    streamBuffer.clear();
                }
                break;
            }
            const QByteArray jpeg = streamBuffer.mid(soi, eoi + 2 - soi);
            streamBuffer.remove(0, eoi + 2);
            QImage image;
            if (!image.loadFromData(jpeg, "JPG") || image.isNull()) {
                continue;
            }
            ++frames;
            const auto now = std::chrono::steady_clock::now();
            if (now - lastEmit >= std::chrono::milliseconds(30)) {
                lastEmit = now;
                if (!firstFrameLogged) {
                    firstFrameLogged = true;
                    emitLog(QStringLiteral("COLOR first frame %1x%2@%3")
                                .arg(image.width())
                                .arg(image.height())
                                .arg(m_config.color.fps));
                    if (image.width() != m_config.color.width || image.height() != m_config.color.height) {
                        emitLog(QStringLiteral("COLOR requested %1x%2, decoded %3x%4")
                                    .arg(m_config.color.width)
                                    .arg(m_config.color.height)
                                    .arg(image.width())
                                    .arg(image.height()));
                    }
                }
                emit colorFrameReady(image.convertToFormat(QImage::Format_RGB888), frames, bytes);
            }
        }
    }
}

void BerxelStreamWorker::depthLoop(UsbDevice& companion)
{
    std::vector<unsigned char> buffer(16384);
    const int frameBytes = depthFrameBytes(m_config.depth);
    gomob::berxel::host::UvcRawFrameAssembler assembler(
        gomob::berxel::host::UvcRawFrameAssemblerConfig{
            0x82,
            toSdkMode(m_config.depth),
            static_cast<size_t>(frameBytes),
            true,
            static_cast<size_t>(frameBytes) * 3,
        });
    quint64 bytes = 0;
    quint64 frames = 0;
    bool firstFrameLogged = false;
    bool headerLogged = false;
    bool resyncLogged = false;
    auto lastEmit = std::chrono::steady_clock::now() - std::chrono::milliseconds(100);

    // 时域降噪 + 飞点剔除（depth 模式专用，跨帧有状态）。
    const BerxelVideoMode activeMode = activeDepthMode(m_config.depth);
    const size_t activePixels =
        static_cast<size_t>(activeMode.width) * static_cast<size_t>(activeMode.height);
    gomob::berxel::host::P100R3TemporalFilter depthFilter;
    std::vector<uint16_t> activeRaw, fused;
    std::vector<uint8_t> conf, flying;
    gomob::berxel::host::P100R3TemporalFilterStats fstats;

    auto emitFrame = [&](const std::vector<unsigned char>& rawFrame) {
        const auto now = std::chrono::steady_clock::now();
        // LIGHT_IR 模式：原样渲染 IR，不跑深度质量管线。
        if (m_config.depth_as_light_ir) {
            ++frames;
            if (now - lastEmit < std::chrono::milliseconds(100)) return;
            lastEmit = now;
            emit depthFrameReady(lightIrToImage(rawFrame, m_config.depth), frames, bytes);
            return;
        }
        // DEPTH 模式：只处理真深度帧（跳过交织的 IR/phase 帧，否则渲染成垃圾）。
        if (!isRealDepthFrame(rawFrame)) return;
        ++frames;
        // 提取 active raw16（前 aw×ah 像素，状态行在最后一行）。
        bool haveFused = false;
        if (rawFrame.size() >= activePixels * 2 && activePixels > 0) {
            const auto* src = reinterpret_cast<const uint16_t*>(rawFrame.data());
            activeRaw.assign(src, src + activePixels);
            haveFused = depthFilter.push(
                activeRaw, static_cast<uint16_t>(activeMode.width),
                static_cast<uint16_t>(activeMode.height), &fused, &conf, &fstats, &flying);
        }
        const int previewIntervalMs = 45;
        if (now - lastEmit < std::chrono::milliseconds(previewIntervalMs)) return;
        lastEmit = now;
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            emitLog(QStringLiteral("DEPTH first frame transport %1x%2 active %3x%4@%5 (时域降噪+飞点剔除已接入)")
                        .arg(m_config.depth.width).arg(m_config.depth.height)
                        .arg(activeMode.width).arg(activeMode.height).arg(activeMode.fps));
        }
        emit depthFrameReady(depthToImage(rawFrame, m_config.depth), frames, bytes);
        if (haveFused) {
            const double flyingPct = fstats.fused_pixels > 0
                ? 100.0 * static_cast<double>(fstats.flying_pixels) /
                      static_cast<double>(fstats.fused_pixels)
                : 0.0;
            const double densityPct = activePixels > 0
                ? 100.0 * static_cast<double>(fstats.fused_pixels) /
                      static_cast<double>(activePixels)
                : 0.0;
            emit depthProcessedFrameReady(depthProcessedToImage(fused, flying, conf, m_config.depth),
                                          frames, fstats.noise_floor_mm, flyingPct, densityPct);
        }
    };

    auto packetNowNs = []() -> int64_t {
        return std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    };

    while (!m_stopRequested.load()) {
        int actual = 0;
        const int rc = companion.bulk_in(0x82, buffer.data(), static_cast<int>(buffer.size()), &actual, 200);
        if (rc != 0 || actual <= 0) {
            continue;
        }
        bytes += static_cast<quint64>(actual);

        std::vector<gomob::berxel::host::UvcRawFrame> rawFrames;
        assembler.push_packet(buffer.data(), actual, packetNowNs(), &rawFrames);
        const gomob::berxel::host::UvcRawFrameAssemblerStats stats = assembler.stats();
        if (stats.uvc_headers > 0) {
            if (!headerLogged) {
                headerLogged = true;
                const QString label = m_config.depth_as_light_ir
                    ? QStringLiteral("LIGHT_IR")
                    : QStringLiteral("DEPTH");
                emitLog(QStringLiteral("%1 stripping UVC payload headers").arg(label));
            }
        }
        if (stats.partial_frame_drops > 0 && !resyncLogged) {
            resyncLogged = true;
            const QString label = m_config.depth_as_light_ir
                ? QStringLiteral("LIGHT_IR")
                : QStringLiteral("DEPTH");
            emitLog(QStringLiteral("%1 resync at UVC header, partial drops=%2")
                        .arg(label)
                        .arg(stats.partial_frame_drops));
        }
        if (stats.oversized_frame_drops > 0 && !resyncLogged) {
            if (!resyncLogged) {
                resyncLogged = true;
                const QString label = m_config.depth_as_light_ir
                    ? QStringLiteral("LIGHT_IR")
                    : QStringLiteral("DEPTH");
                emitLog(QStringLiteral("%1 resync, keep tail %2 bytes")
                            .arg(label)
                            .arg(frameBytes));
            }
        }
        for (const gomob::berxel::host::UvcRawFrame& rawFrame : rawFrames) {
            emitFrame(rawFrame.payload);
        }
    }
}

QImage BerxelStreamWorker::depthToImage(const std::vector<unsigned char>& frame, const BerxelVideoMode& mode)
{
    const BerxelVideoMode active = activeDepthMode(mode);
    QImage image(active.width, active.height, QImage::Format_RGB888);
    if (static_cast<int>(frame.size()) < depthFrameBytes(mode)) {
        image.fill(Qt::black);
        return image;
    }

    const float range = kDepthPreviewMaxMm - kDepthPreviewMinMm;
    for (int y = 0; y < active.height; ++y) {
        unsigned char* line = image.scanLine(y);
        for (int x = 0; x < active.width; ++x) {
            const size_t byteIndex =
                (static_cast<size_t>(y) * static_cast<size_t>(mode.width) +
                 static_cast<size_t>(x)) *
                sizeof(uint16_t);
            const uint16_t raw = static_cast<uint16_t>(frame[byteIndex]) |
                                 static_cast<uint16_t>(
                                     static_cast<uint16_t>(frame[byteIndex + 1]) << 8);
            const float mm = gomob::berxel::host::p100r3_depth_raw_to_mm(
                raw,
                gomob::berxel::host::P100R3DepthPixelFormat::k13I3D);
            unsigned char r = 0;
            unsigned char g = 0;
            unsigned char b = 0;
            if (mm >= kDepthPreviewMinMm && mm <= kDepthPreviewMaxMm) {
                const float t = std::max(0.0f,
                                         std::min(1.0f,
                                                  (mm - kDepthPreviewMinMm) / range));
                r = clampByte(static_cast<int>(255.0f * t));
                g = clampByte(static_cast<int>(220.0f * (1.0f - std::abs(t - 0.45f) * 1.8f)));
                b = clampByte(static_cast<int>(255.0f * (1.0f - t)));
            }
            line[x * 3 + 0] = r;
            line[x * 3 + 1] = g;
            line[x * 3 + 2] = b;
        }
    }
    return image;
}

QImage BerxelStreamWorker::depthProcessedToImage(const std::vector<uint16_t>& fusedActiveRaw,
                                                 const std::vector<uint8_t>& flyingMask,
                                                 const std::vector<uint8_t>& confidence,
                                                 const BerxelVideoMode& mode)
{
    const BerxelVideoMode active = activeDepthMode(mode);
    QImage image(active.width, active.height, QImage::Format_RGB888);
    const size_t px = static_cast<size_t>(active.width) * static_cast<size_t>(active.height);
    if (fusedActiveRaw.size() < px) {
        image.fill(Qt::black);
        return image;
    }
    const bool haveFly = flyingMask.size() >= px;
    const bool haveConf = confidence.size() >= px;
    // 真置信掩码：低置信(时域不稳)像素出黑洞，只显示可信深度——这才是测量级视图。
    // 数据本身保稠密(fused 全保留)，这里只是渲染时按 conf 掩码给人看。
    constexpr int kProcessedConfThreshold = 160;
    const float range = kDepthPreviewMaxMm - kDepthPreviewMinMm;
    for (int y = 0; y < active.height; ++y) {
        unsigned char* line = image.scanLine(y);
        for (int x = 0; x < active.width; ++x) {
            const size_t i = static_cast<size_t>(y) * static_cast<size_t>(active.width) + x;
            unsigned char r = 0, g = 0, b = 0;
            if (haveFly && flyingMask[i] != 0) {
                // 飞点高亮品红，让用户直接看到被剔除的悬浮点
                r = 255; g = 0; b = 200;
            } else if (haveConf && confidence[i] < kProcessedConfThreshold) {
                // 低置信=时域不稳/不可信 → 出黑洞（多视角主线由其它视角补）
                r = 0; g = 0; b = 0;
            } else {
                const float mm = gomob::berxel::host::p100r3_depth_raw_to_mm(
                    fusedActiveRaw[i], gomob::berxel::host::P100R3DepthPixelFormat::k13I3D);
                if (mm >= kDepthPreviewMinMm && mm <= kDepthPreviewMaxMm) {
                    const float t = std::max(0.0f, std::min(1.0f, (mm - kDepthPreviewMinMm) / range));
                    r = clampByte(static_cast<int>(255.0f * t));
                    g = clampByte(static_cast<int>(220.0f * (1.0f - std::abs(t - 0.45f) * 1.8f)));
                    b = clampByte(static_cast<int>(255.0f * (1.0f - t)));
                }
            }
            line[x * 3 + 0] = r;
            line[x * 3 + 1] = g;
            line[x * 3 + 2] = b;
        }
    }
    return image;
}

QImage BerxelStreamWorker::lightIrToImage(const std::vector<unsigned char>& frame,
                                          const BerxelVideoMode& mode)
{
    const BerxelVideoMode active = activeDepthMode(mode);
    QImage image(active.width, active.height, QImage::Format_RGB888);
    if (static_cast<int>(frame.size()) < depthFrameBytes(mode)) {
        image.fill(Qt::black);
        return image;
    }

    std::vector<uint16_t> activeIr;
    const bool ok = gomob::berxel::host::process_p100r3_light_ir_frame(
        frame.data(),
        frame.size(),
        toSdkMode(mode),
        &activeIr);
    if (!ok || activeIr.size() < static_cast<size_t>(active.width * active.height)) {
        image.fill(Qt::black);
        return image;
    }

    const int range = static_cast<int>(kLightIrPreviewMax - kLightIrPreviewMin);

    for (int y = 0; y < active.height; ++y) {
        unsigned char* line = image.scanLine(y);
        for (int x = 0; x < active.width; ++x) {
            const size_t pixelIndex = static_cast<size_t>(y) * active.width + x;
            const int value = static_cast<int>(activeIr[pixelIndex]);
            const unsigned char gray = value <= kLightIrPreviewMin
                ? 0
                : clampByte(((std::min(value, static_cast<int>(kLightIrPreviewMax)) -
                               static_cast<int>(kLightIrPreviewMin)) * 255) /
                            range);
            line[x * 3 + 0] = gray;
            line[x * 3 + 1] = gray;
            line[x * 3 + 2] = gray;
        }
    }
    return image;
}

void BerxelStreamWorker::sendMasterStop(UsbDevice& master)
{
    std::vector<XuPayload> stops;
    if (m_config.depth_as_light_ir) {
        stops.push_back(gomob::berxel::host::make_p100r3_master_force_internal_pwm_trigger_payload(false, 0));
    }
    stops.push_back(gomob::berxel::host::make_p100r3_master_close_stream_payload(1));
    if (!m_config.depth_as_light_ir) {
        stops.push_back(gomob::berxel::host::make_p100r3_master_close_stream_payload(2));
        stops.push_back(gomob::berxel::host::make_p100r3_master_close_stream_payload(5));
    }
    gomob::berxel::host::replay_xu_payloads(
        master,
        stops,
        true,
        "master-stop",
        [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); });
}

void BerxelStreamWorker::sendCompanionStop(UsbDevice& companion)
{
    const XuPayload payload =
        gomob::berxel::host::make_p100r3_companion_hv3_command_payload({0x01, 0x02, 0x00});
    gomob::berxel::host::replay_xu_payloads(
        companion,
        {payload},
        true,
        "companion-light-ir-stop",
        [this](const std::string& msg) { emitLog(QString::fromStdString(msg)); });
}

std::vector<unsigned char> BerxelStreamWorker::jpegPayload(const unsigned char* data, int actual)
{
    const UvcPayloadView payload = parseUvcPayload(data, actual);
    if (payload.length <= 0) return {};
    return std::vector<unsigned char>(data + payload.offset, data + payload.offset + payload.length);
}
