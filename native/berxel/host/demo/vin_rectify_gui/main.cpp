#include "MainWindow.h"

#include <QApplication>
#include <QStringList>

namespace {

int intOption(const QStringList& args, const QString& name)
{
    const int index = args.indexOf(name);
    if (index < 0 || index + 1 >= args.size()) return 0;
    return args[index + 1].toInt();
}

}  // namespace

int main(int argc, char** argv)
{
    QApplication app(argc, argv);
    const QStringList args = app.arguments();
    const bool autoStart = args.contains(QStringLiteral("--auto-start"));
    const int exitAfterMs = intOption(args, QStringLiteral("--exit-after-ms"));
    const int colorFrameIndex = intOption(args, QStringLiteral("--color-frame"));
    const int depthFrameIndex = intOption(args, QStringLiteral("--depth-frame"));
    const bool lightIr = args.contains(QStringLiteral("--light-ir"));

    MainWindow window(autoStart, exitAfterMs, colorFrameIndex, depthFrameIndex, lightIr);
    window.show();
    return app.exec();
}
