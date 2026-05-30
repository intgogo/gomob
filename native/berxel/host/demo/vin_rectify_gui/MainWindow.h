#pragma once

#include "BerxelStreamWorker.h"

#include <QElapsedTimer>
#include <QMainWindow>

class ImageView;
class QCheckBox;
class QComboBox;
class QLabel;
class QPushButton;
class QPlainTextEdit;

#include <vector>

class MainWindow : public QMainWindow
{
    Q_OBJECT

public:
    explicit MainWindow(bool autoStart,
                        int exitAfterMs,
                        int colorFrameIndex,
                        int depthFrameIndex,
                        bool lightIr,
                        QWidget* parent = nullptr);
    ~MainWindow() override;

protected:
    void closeEvent(QCloseEvent* event) override;

private slots:
    void startPreview();
    void stopPreview();
    void workerStopped();
    void workerFailed(const QString& message);
    void updateColorFrame(const QImage& image, quint64 frameIndex, quint64 bytes);
    void updateDepthFrame(const QImage& image, quint64 frameIndex, quint64 bytes);
    void updateDepthProcessedFrame(const QImage& image, quint64 frameIndex,
                                   double noiseFloorMm, double flyingPct, double densityPct);
    void appendLog(const QString& message);

private:
    QWidget* buildHeader();
    QWidget* buildControls();
    QWidget* buildImageArea();
    QWidget* buildLogPanel();
    void setRunning(bool running);
    QString formatStats(quint64 frames, quint64 bytes) const;
    QString modeText(const BerxelVideoMode& mode, const QString& suffix) const;
    QString depthSourceLabel(const BerxelStreamConfig& config) const;
    QString depthSuffix(const BerxelStreamConfig& config) const;
    BerxelStreamConfig selectedConfig() const;
    void refreshModeSubtitles();

    ImageView* m_colorView;
    ImageView* m_depthView;
    ImageView* m_depthProcessedView;
    QLabel* m_statusLabel;
    QLabel* m_colorStatsLabel;
    QLabel* m_depthStatsLabel;
    QLabel* m_depthProcessedStatsLabel;
    QComboBox* m_colorModeCombo;
    QComboBox* m_depthModeCombo;
    QCheckBox* m_lightIrCheckBox;
    QCheckBox* m_depthOnlyCheckBox;
    QPushButton* m_startButton;
    QPushButton* m_stopButton;
    QPlainTextEdit* m_log;
    BerxelStreamWorker* m_worker;
    QElapsedTimer m_timer;
    std::vector<BerxelVideoMode> m_colorModes;
    std::vector<BerxelVideoMode> m_depthModes;
    quint64 m_colorFrames;
    quint64 m_colorBytes;
    quint64 m_depthFrames;
    quint64 m_depthBytes;
};
