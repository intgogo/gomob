// 简单包装 log/slog；后续可扩到 zap / logrus。
package logger

import (
	"log/slog"
	"os"
)

func New(service string) *slog.Logger {
	return slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	})).With("service", service)
}
