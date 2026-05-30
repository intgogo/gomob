#include "MainWindow.h"

#include "BerxelStreamWorker.h"
#include "ImageView.h"

#include <QApplication>
#include <QCheckBox>
#include <QCloseEvent>
#include <QDateTime>
#include <QComboBox>
#include <QFormLayout>
#include <QFrame>
#include <QGridLayout>
#include <QHBoxLayout>
#include <QLabel>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QTimer>
#include <QVBoxLayout>

#include <algorithm>
#include <cstdio>

namespace {

void selectModeByFrameIndex(QComboBox* combo,
                            const std::vector<BerxelVideoMode>& modes,
                            int frameIndex)
{
    if (!combo || frameIndex <= 0) return;
    for (size_t i = 0; i < modes.size(); ++i) {
        if (modes[i].frame_index == frameIndex) {
            combo->setCurrentIndex(static_cast<int>(i));
            return;
        }
    }
}

}  // namespace

MainWindow::MainWindow(bool autoStart,
                       int exitAfterMs,
                       int colorFrameIndex,
                       int depthFrameIndex,
                       bool lightIr,
                       QWidget* parent)
    : QMainWindow(parent),
      m_colorView(nullptr),
      m_depthView(nullptr),
      m_depthProcessedView(nullptr),
      m_statusLabel(nullptr),
      m_colorStatsLabel(nullptr),
      m_depthStatsLabel(nullptr),
      m_depthProcessedStatsLabel(nullptr),
      m_colorModeCombo(nullptr),
      m_depthModeCombo(nullptr),
      m_lightIrCheckBox(nullptr),
      m_depthOnlyCheckBox(nullptr),
      m_startButton(nullptr),
      m_stopButton(nullptr),
      m_log(nullptr),
      m_worker(nullptr),
      m_colorModes({
          {1, 1920, 1080, 30, 333333},
          {2, 1280, 800, 30, 333333},
          {3, 640, 400, 30, 333333},
      }),
      m_depthModes({
          {1, 1280, 801, 45, 222222},
          {2, 640, 401, 45, 222222},
          {3, 320, 201, 45, 222222},
          {4, 1280, 800, 5, 2000000},
      }),
      m_colorFrames(0),
      m_colorBytes(0),
      m_depthFrames(0),
      m_depthBytes(0)
{
    setWindowTitle(QStringLiteral("Gomob Berxel Vin Rectify Preview"));
    resize(1320, 820);

    QWidget* central = new QWidget(this);
    QVBoxLayout* root = new QVBoxLayout(central);
    root->setContentsMargins(18, 16, 18, 18);
    root->setSpacing(14);
    root->addWidget(buildHeader());

    QHBoxLayout* body = new QHBoxLayout();
    body->setSpacing(14);
    body->addWidget(buildControls(), 0);
    body->addWidget(buildImageArea(), 1);
    root->addLayout(body, 1);
    root->addWidget(buildLogPanel(), 0);
    setCentralWidget(central);
    selectModeByFrameIndex(m_colorModeCombo, m_colorModes, colorFrameIndex);
    selectModeByFrameIndex(m_depthModeCombo, m_depthModes, depthFrameIndex);
    if (m_lightIrCheckBox) {
        m_lightIrCheckBox->setChecked(lightIr);
    }
    refreshModeSubtitles();

    setStyleSheet(QStringLiteral(
        "QWidget { background: #eef5f4; color: #173235; font-size: 14px; }"
        "QFrame#Header, QFrame#Controls, QFrame#ImageView, QFrame#LogPanel {"
        "  background: #ffffff; border: 1px solid #cadbdb; border-radius: 8px;"
        "}"
        "QLabel#Title { font-size: 24px; font-weight: 700; color: #0f5f57; }"
        "QLabel#Subtle, QLabel#ImageSubtitle { color: #587176; }"
        "QLabel#ImageTitle { font-size: 16px; font-weight: 700; color: #183f43; }"
        "QLabel#ImageCanvas { background: #071213; color: #91a5a7; border-radius: 4px; }"
        "QLabel#Metric { background: #f2f8f8; border: 1px solid #d7e6e6; border-radius: 6px; padding: 8px; }"
        "QComboBox { background: #ffffff; border: 1px solid #bfd2d2; border-radius: 6px; padding: 7px 8px; }"
        "QCheckBox { spacing: 8px; color: #183f43; }"
        "QCheckBox::indicator { width: 18px; height: 18px; }"
        "QPushButton { background: #0f6f5f; color: white; border: 0; border-radius: 6px; padding: 9px 14px; font-weight: 700; }"
        "QPushButton:disabled { background: #a9bcbd; }"
        "QPushButton#StopButton { background: #80463f; }"
        "QPlainTextEdit { background: #0c191b; color: #cce8e5; border-radius: 6px; padding: 8px; }"
    ));

    setRunning(false);
    if (autoStart) {
        QTimer::singleShot(250, this, &MainWindow::startPreview);
    }
    if (exitAfterMs > 0) {
        QTimer::singleShot(exitAfterMs, this, [this]() {
            stopPreview();
            if (!m_worker) {
                qApp->quit();
            } else {
                connect(m_worker, &BerxelStreamWorker::streamStopped, qApp, &QApplication::quit, Qt::SingleShotConnection);
            }
        });
    }
}

MainWindow::~MainWindow()
{
    stopPreview();
    if (m_worker) {
        m_worker->wait(15000);
    }
}

void MainWindow::closeEvent(QCloseEvent* event)
{
    stopPreview();
    if (m_worker) {
        m_worker->wait(15000);
    }
    QMainWindow::closeEvent(event);
}

QWidget* MainWindow::buildHeader()
{
    QFrame* frame = new QFrame(this);
    frame->setObjectName("Header");
    QHBoxLayout* layout = new QHBoxLayout(frame);
    layout->setContentsMargins(16, 12, 16, 12);
    layout->setSpacing(12);

    QVBoxLayout* titleBox = new QVBoxLayout();
    titleBox->setSpacing(4);
    QLabel* title = new QLabel(QStringLiteral("Vin Rectify Host Preview"), frame);
    title->setObjectName("Title");
    QLabel* subtitle = new QLabel(QStringLiteral("P100R3 自研 libusb 双流预览"), frame);
    subtitle->setObjectName("Subtle");
    titleBox->addWidget(title);
    titleBox->addWidget(subtitle);
    layout->addLayout(titleBox, 1);

    m_statusLabel = new QLabel(QStringLiteral("待机"), frame);
    m_statusLabel->setObjectName("Metric");
    m_statusLabel->setMinimumWidth(180);
    m_statusLabel->setAlignment(Qt::AlignCenter);
    layout->addWidget(m_statusLabel);
    return frame;
}

QWidget* MainWindow::buildControls()
{
    QFrame* frame = new QFrame(this);
    frame->setObjectName("Controls");
    frame->setMinimumWidth(260);
    frame->setMaximumWidth(320);
    QVBoxLayout* layout = new QVBoxLayout(frame);
    layout->setContentsMargins(14, 14, 14, 14);
    layout->setSpacing(12);

    m_colorModeCombo = new QComboBox(frame);
    m_depthModeCombo = new QComboBox(frame);
    m_lightIrCheckBox = new QCheckBox(QStringLiteral("LIGHT_IR 散斑"), frame);
    m_depthOnlyCheckBox = new QCheckBox(QStringLiteral("仅 DEPTH（避免 color 挂 master）"), frame);
    m_depthOnlyCheckBox->setChecked(true);  // 安全默认：color 流会挂死 master
    for (const BerxelVideoMode& mode : m_colorModes) {
        m_colorModeCombo->addItem(modeText(mode, QStringLiteral("MJPEG")));
    }
    for (const BerxelVideoMode& mode : m_depthModes) {
        m_depthModeCombo->addItem(modeText(mode, QStringLiteral("RAW16")));
    }
    m_colorModeCombo->setCurrentIndex(2);
    m_depthModeCombo->setCurrentIndex(1);
    connect(m_colorModeCombo, &QComboBox::currentIndexChanged, this, [this](int) { refreshModeSubtitles(); });
    connect(m_depthModeCombo, &QComboBox::currentIndexChanged, this, [this](int) { refreshModeSubtitles(); });
    connect(m_lightIrCheckBox, &QCheckBox::toggled, this, [this](bool checked) {
        if (!m_worker && m_depthView) {
            m_depthView->clearImage(checked ? QStringLiteral("LIGHT_IR") : QStringLiteral("DEPTH"));
        }
        refreshModeSubtitles();
    });

    QFormLayout* modeForm = new QFormLayout();
    modeForm->setLabelAlignment(Qt::AlignLeft);
    modeForm->setFormAlignment(Qt::AlignTop);
    modeForm->setContentsMargins(0, 0, 0, 0);
    modeForm->setSpacing(8);
    modeForm->addRow(QStringLiteral("COLOR"), m_colorModeCombo);
    modeForm->addRow(QStringLiteral("DEPTH"), m_depthModeCombo);
    modeForm->addRow(QStringLiteral("输出"), m_lightIrCheckBox);
    modeForm->addRow(QString(), m_depthOnlyCheckBox);
    layout->addLayout(modeForm);

    m_startButton = new QPushButton(QStringLiteral("Start Dual"), frame);
    m_stopButton = new QPushButton(QStringLiteral("Stop"), frame);
    m_stopButton->setObjectName("StopButton");
    connect(m_startButton, &QPushButton::clicked, this, &MainWindow::startPreview);
    connect(m_stopButton, &QPushButton::clicked, this, &MainWindow::stopPreview);
    layout->addWidget(m_startButton);
    layout->addWidget(m_stopButton);

    m_colorStatsLabel = new QLabel(frame);
    m_colorStatsLabel->setObjectName("Metric");
    m_depthStatsLabel = new QLabel(frame);
    m_depthStatsLabel->setObjectName("Metric");
    m_depthProcessedStatsLabel = new QLabel(frame);
    m_depthProcessedStatsLabel->setObjectName("Metric");
    m_depthProcessedStatsLabel->setWordWrap(true);
    layout->addWidget(m_colorStatsLabel);
    layout->addWidget(m_depthStatsLabel);
    layout->addWidget(m_depthProcessedStatsLabel);
    layout->addStretch(1);
    return frame;
}

QWidget* MainWindow::buildImageArea()
{
    QWidget* container = new QWidget(this);
    QGridLayout* grid = new QGridLayout(container);
    grid->setContentsMargins(0, 0, 0, 0);
    grid->setSpacing(14);

    m_colorView = new ImageView(QStringLiteral("COLOR"), container);
    m_depthView = new ImageView(QStringLiteral("DEPTH (raw)"), container);
    m_depthProcessedView = new ImageView(QStringLiteral("DEPTH (时域降噪+飞点剔除)"), container);
    m_colorView->clearImage(QStringLiteral("COLOR"));
    m_depthView->clearImage(QStringLiteral("DEPTH (raw)"));
    m_depthProcessedView->clearImage(QStringLiteral("处理后"));
    refreshModeSubtitles();
    grid->addWidget(m_colorView, 0, 0);
    grid->addWidget(m_depthView, 0, 1);
    grid->addWidget(m_depthProcessedView, 0, 2);
    grid->setColumnStretch(0, 1);
    grid->setColumnStretch(1, 1);
    grid->setColumnStretch(2, 1);
    return container;
}

QWidget* MainWindow::buildLogPanel()
{
    QFrame* frame = new QFrame(this);
    frame->setObjectName("LogPanel");
    QVBoxLayout* layout = new QVBoxLayout(frame);
    layout->setContentsMargins(14, 12, 14, 14);
    layout->setSpacing(8);
    QLabel* title = new QLabel(QStringLiteral("Log"), frame);
    title->setObjectName("ImageTitle");
    m_log = new QPlainTextEdit(frame);
    m_log->setReadOnly(true);
    m_log->setMaximumBlockCount(300);
    m_log->setFixedHeight(130);
    layout->addWidget(title);
    layout->addWidget(m_log);
    return frame;
}

void MainWindow::startPreview()
{
    if (m_worker) {
        return;
    }
    const BerxelStreamConfig config = selectedConfig();
    const QString depthLabel = depthSourceLabel(config);
    const QString depthModeSuffix = depthSuffix(config);
    m_colorFrames = 0;
    m_colorBytes = 0;
    m_depthFrames = 0;
    m_depthBytes = 0;
    m_timer.restart();
    appendLog(QStringLiteral("启动双流预览 COLOR %1 %2 %3")
                  .arg(modeText(config.color, QStringLiteral("MJPEG")))
                  .arg(depthLabel)
                  .arg(modeText(config.depth, depthModeSuffix)));
    setRunning(true);

    m_worker = new BerxelStreamWorker(config, this);
    connect(m_worker, &BerxelStreamWorker::logMessage, this, &MainWindow::appendLog);
    connect(m_worker, &BerxelStreamWorker::statusChanged, m_statusLabel, &QLabel::setText);
    connect(m_worker, &BerxelStreamWorker::streamFailed, this, &MainWindow::workerFailed);
    connect(m_worker, &BerxelStreamWorker::streamStopped, this, &MainWindow::workerStopped);
    connect(m_worker, &BerxelStreamWorker::colorFrameReady, this, &MainWindow::updateColorFrame);
    connect(m_worker, &BerxelStreamWorker::depthFrameReady, this, &MainWindow::updateDepthFrame);
    connect(m_worker, &BerxelStreamWorker::depthProcessedFrameReady,
            this, &MainWindow::updateDepthProcessedFrame);
    m_worker->start();
}

void MainWindow::stopPreview()
{
    if (!m_worker) {
        return;
    }
    appendLog(QStringLiteral("请求停止"));
    m_statusLabel->setText(QStringLiteral("停止中"));
    m_worker->requestStop();
}

void MainWindow::workerStopped()
{
    if (!m_worker) {
        return;
    }
    appendLog(QStringLiteral("预览已停止"));
    m_worker->wait(1000);
    m_worker->deleteLater();
    m_worker = nullptr;
    setRunning(false);
}

void MainWindow::workerFailed(const QString& message)
{
    appendLog(QStringLiteral("错误：%1").arg(message));
    m_statusLabel->setText(QStringLiteral("错误"));
}

void MainWindow::updateColorFrame(const QImage& image, quint64 frameIndex, quint64 bytes)
{
    m_colorFrames = frameIndex;
    m_colorBytes = bytes;
    m_colorView->setImage(image);
    m_colorStatsLabel->setText(formatStats(m_colorFrames, m_colorBytes));
}

void MainWindow::updateDepthFrame(const QImage& image, quint64 frameIndex, quint64 bytes)
{
    m_depthFrames = frameIndex;
    m_depthBytes = bytes;
    m_depthView->setImage(image);
    m_depthStatsLabel->setText(formatStats(m_depthFrames, m_depthBytes));
}

void MainWindow::updateDepthProcessedFrame(const QImage& image, quint64 /*frameIndex*/,
                                           double noiseFloorMm, double flyingPct, double densityPct)
{
    if (m_depthProcessedView) {
        m_depthProcessedView->setImage(image);
    }
    if (m_depthProcessedStatsLabel) {
        // 噪声底 = 自适应估计（≈ raw 逐帧抖动）；运动门限随它走，时域融合后实际抖动远低于此。
        m_depthProcessedStatsLabel->setText(
            QStringLiteral("噪声底 %1mm  飞点 %2%  密度 %3%\n品红=被剔飞点")
                .arg(noiseFloorMm, 0, 'f', 1)
                .arg(flyingPct, 0, 'f', 2)
                .arg(densityPct, 0, 'f', 1));
    }
}

void MainWindow::appendLog(const QString& message)
{
    const QString line = QStringLiteral("[%1] %2")
                             .arg(QDateTime::currentDateTime().toString(QStringLiteral("HH:mm:ss.zzz")))
                             .arg(message);
    m_log->appendPlainText(line);
    const QByteArray utf8 = line.toUtf8();
    std::fprintf(stderr, "%s\n", utf8.constData());
    std::fflush(stderr);
}

void MainWindow::setRunning(bool running)
{
    m_startButton->setEnabled(!running);
    m_stopButton->setEnabled(running);
    m_colorModeCombo->setEnabled(!running);
    m_depthModeCombo->setEnabled(!running);
    m_lightIrCheckBox->setEnabled(!running);
    if (m_depthOnlyCheckBox) m_depthOnlyCheckBox->setEnabled(!running);
    if (!running) {
        m_statusLabel->setText(QStringLiteral("待机"));
    }
    m_colorStatsLabel->setText(formatStats(m_colorFrames, m_colorBytes));
    m_depthStatsLabel->setText(formatStats(m_depthFrames, m_depthBytes));
}

QString MainWindow::formatStats(quint64 frames, quint64 bytes) const
{
    const double seconds = std::max(0.001, static_cast<double>(m_timer.elapsed()) / 1000.0);
    const double fps = static_cast<double>(frames) / seconds;
    const double mb = static_cast<double>(bytes) / (1024.0 * 1024.0);
    return QStringLiteral("frames %1\nfps %2\nbytes %3 MB")
        .arg(frames)
        .arg(fps, 0, 'f', 1)
        .arg(mb, 0, 'f', 2);
}

QString MainWindow::modeText(const BerxelVideoMode& mode, const QString& suffix) const
{
    return QStringLiteral("%1x%2 @ %3fps %4")
        .arg(mode.width)
        .arg(mode.height)
        .arg(mode.fps)
        .arg(suffix);
}

QString MainWindow::depthSourceLabel(const BerxelStreamConfig& config) const
{
    return config.depth_as_light_ir ? QStringLiteral("LIGHT_IR") : QStringLiteral("DEPTH");
}

QString MainWindow::depthSuffix(const BerxelStreamConfig& config) const
{
    return config.depth_as_light_ir ? QStringLiteral("IR10") : QStringLiteral("RAW16");
}

BerxelStreamConfig MainWindow::selectedConfig() const
{
    const int colorIndex = std::max(0, m_colorModeCombo ? m_colorModeCombo->currentIndex() : 0);
    const int depthIndex = std::max(0, m_depthModeCombo ? m_depthModeCombo->currentIndex() : 0);
    BerxelStreamConfig config;
    config.color = m_colorModes[static_cast<size_t>(std::min(colorIndex, static_cast<int>(m_colorModes.size()) - 1))];
    config.depth = m_depthModes[static_cast<size_t>(std::min(depthIndex, static_cast<int>(m_depthModes.size()) - 1))];
    config.depth_as_light_ir = m_lightIrCheckBox && m_lightIrCheckBox->isChecked();
    config.depth_only = m_depthOnlyCheckBox && m_depthOnlyCheckBox->isChecked();
    return config;
}

void MainWindow::refreshModeSubtitles()
{
    if (!m_colorView || !m_depthView || !m_colorModeCombo || !m_depthModeCombo) {
        return;
    }
    const BerxelStreamConfig config = selectedConfig();
    m_colorView->setSubtitle(modeText(config.color, QStringLiteral("MJPEG")));
    m_depthView->setTitle(depthSourceLabel(config));
    m_depthView->setSubtitle(modeText(config.depth, depthSuffix(config)));
}
