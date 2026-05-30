#pragma once

#include <QFrame>
#include <QImage>
#include <QSize>

class QLabel;

class ImageView : public QFrame
{
    Q_OBJECT

public:
    explicit ImageView(const QString& title, QWidget* parent = nullptr);

    void setImage(const QImage& image);
    void clearImage(const QString& message);
    void setTitle(const QString& text);
    void setSubtitle(const QString& text);
    QSize imageSize() const;

protected:
    void resizeEvent(QResizeEvent* event) override;

private:
    void refreshPixmap();

    QLabel* m_titleLabel;
    QLabel* m_subtitleLabel;
    QLabel* m_imageLabel;
    QImage m_image;
    QPixmap m_scaledPixmap;
    QSize m_scaledTarget;
    QSize m_scaledImageSize;
};
