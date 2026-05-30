#include "ImageView.h"

#include <QLabel>
#include <QPixmap>
#include <QResizeEvent>
#include <QVBoxLayout>

ImageView::ImageView(const QString& title, QWidget* parent)
    : QFrame(parent),
      m_titleLabel(new QLabel(title, this)),
      m_subtitleLabel(new QLabel(this)),
      m_imageLabel(new QLabel(this))
{
    setObjectName("ImageView");
    setFrameShape(QFrame::NoFrame);
    setMinimumSize(360, 260);

    m_titleLabel->setObjectName("ImageTitle");
    m_subtitleLabel->setObjectName("ImageSubtitle");
    m_imageLabel->setObjectName("ImageCanvas");
    m_imageLabel->setAlignment(Qt::AlignCenter);
    m_imageLabel->setMinimumSize(320, 220);
    m_imageLabel->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Expanding);

    QVBoxLayout* layout = new QVBoxLayout(this);
    layout->setContentsMargins(14, 12, 14, 14);
    layout->setSpacing(8);
    layout->addWidget(m_titleLabel);
    layout->addWidget(m_imageLabel, 1);
    layout->addWidget(m_subtitleLabel);
}

void ImageView::setImage(const QImage& image)
{
    m_image = image.copy();
    m_scaledPixmap = QPixmap();
    m_scaledTarget = QSize();
    m_scaledImageSize = QSize();
    refreshPixmap();
}

void ImageView::clearImage(const QString& message)
{
    m_image = QImage();
    m_scaledPixmap = QPixmap();
    m_scaledTarget = QSize();
    m_scaledImageSize = QSize();
    m_imageLabel->clear();
    m_imageLabel->setText(message);
}

void ImageView::setTitle(const QString& text)
{
    m_titleLabel->setText(text);
}

void ImageView::setSubtitle(const QString& text)
{
    m_subtitleLabel->setText(text);
}

QSize ImageView::imageSize() const
{
    return m_image.size();
}

void ImageView::resizeEvent(QResizeEvent* event)
{
    QFrame::resizeEvent(event);
    refreshPixmap();
}

void ImageView::refreshPixmap()
{
    if (m_image.isNull()) {
        return;
    }
    const QSize target = m_imageLabel->size() - QSize(4, 4);
    if (target.width() <= 0 || target.height() <= 0) {
        return;
    }
    if (m_scaledPixmap.isNull() || m_scaledTarget != target || m_scaledImageSize != m_image.size()) {
        m_scaledPixmap = QPixmap::fromImage(m_image).scaled(target, Qt::KeepAspectRatio, Qt::SmoothTransformation);
        m_scaledTarget = target;
        m_scaledImageSize = m_image.size();
    }
    m_imageLabel->setPixmap(m_scaledPixmap);
}
