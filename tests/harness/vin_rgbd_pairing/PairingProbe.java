package io.gomob.feature.scan3d;

import io.gomob.model.CameraIntrinsics;
import io.gomob.model.ColorFrame;
import io.gomob.model.DepthFrame;
import io.gomob.model.DepthSampleFormat;
import io.gomob.model.RgbdFramePair;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;

/** 独立验证 VIN 5fps 回调边界、快门 burst、水位与全局最优配对。 */
public final class PairingProbe {
    public static void main(String[] args) throws Exception {
        AtomicLong nowUs = new AtomicLong(1_200_000L);
        VinRgbdPairer pairer = pairer(nowUs, 100_000L, 250_000L);
        pairer.offerColor(color(1_000_000L, 1));
        pairer.offerDepth(depth(1_054_300L, 1));
        RgbdFramePair phasePair = requirePair(pairer.snapshot(Long.MIN_VALUE), "54.3ms 固定相位应通过");
        require(phasePair.getTimestampDeltaUs() == 54_300L, "固定相位差错误");

        VinRgbdPairer boundary = pairer(nowUs, 100_000L, 250_000L);
        boundary.offerColor(color(1_000_000L, 1));
        boundary.offerDepth(depth(1_100_000L, 1));
        requirePair(boundary.snapshot(Long.MIN_VALUE), "100ms 边界应通过");

        VinRgbdPairer rejected = pairer(nowUs, 100_000L, 250_000L);
        rejected.offerColor(color(1_000_000L, 1));
        rejected.offerDepth(depth(1_100_001L, 1));
        require(rejected.snapshot(Long.MIN_VALUE) == null, "100001us 必须拒绝");

        nowUs.set(3_080_000L);
        VinRgbdPairer waiting = pairer(nowUs, 100_000L, 250_000L);
        waiting.offerColor(color(3_000_000L, 1));
        waiting.offerDepth(depth(3_054_300L, 1));
        long requestUs = 3_060_000L;
        Thread feeder = new Thread(() -> {
            try {
                Thread.sleep(30L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            nowUs.set(3_280_000L);
            waiting.offerColor(color(3_200_000L, 2));
            waiting.offerDepth(depth(3_254_300L, 2));
        });
        feeder.start();
        long awaitStartNs = System.nanoTime();
        RgbdFramePair awaited = await(waiting, requestUs, 500L);
        long awaitMs = (System.nanoTime() - awaitStartNs) / 1_000_000L;
        feeder.join();
        requirePair(awaited, "点击后的新帧等待失败");
        require(awaited.getColor().getFrameIndex() == 2, "错误复用了点击前彩色帧");
        require(awaited.getDepth().getFrameIndex() == 2, "错误复用了点击前深度帧");

        long timeoutStartNs = System.nanoTime();
        RgbdFramePair reused = await(waiting, 3_260_000L, 80L);
        long timeoutMs = (System.nanoTime() - timeoutStartNs) / 1_000_000L;
        require(reused == null, "连续快门不得复用上一组帧");

        AtomicLong staleNowUs = new AtomicLong(4_080_000L);
        VinRgbdPairer stale = pairer(staleNowUs, 100_000L, 100_000L);
        stale.offerColor(color(4_000_000L, 1));
        stale.offerDepth(depth(4_054_300L, 1));
        require(stale.nearestDeltaUs(Long.MIN_VALUE) == 54_300L, "新鲜诊断差错误");
        staleNowUs.set(4_200_000L);
        require(stale.nearestDeltaUs(Long.MIN_VALUE) == null, "陈旧帧不应进入诊断");

        AtomicLong burstNowUs = new AtomicLong(12_200_000L);
        VinRgbdPairer burstPairer = pairer(burstNowUs, 100_000L, 100_000L);
        Thread burstFeeder = new Thread(() -> {
            try {
                Thread.sleep(30L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long[] depths = {11_154_000L, 11_354_000L, 11_554_000L, 11_790_000L, 11_950_000L, 12_104_000L};
            long[] colors = {11_100_000L, 11_300_000L, 11_500_000L, 11_700_000L, 11_900_000L, 12_100_000L};
            for (int i = 0; i < depths.length; i++) burstPairer.offerDepth(depth(depths[i], i + 1));
            for (int i = 0; i < colors.length; i++) burstPairer.offerColor(color(colors[i], i + 1));
        });
        burstFeeder.start();
        long burstStartNs = System.nanoTime();
        VinRgbdBurstResult burst = awaitBurst(burstPairer, 11_000_000L, 3, 3, 3, 1_000L);
        long burstMs = (System.nanoTime() - burstStartNs) / 1_000_000L;
        burstFeeder.join();
        require(!burst.getTimedOut(), "burst 不应超时");
        require(burst.getColorCount() == 3, "跳过3帧后必须保留3张彩色候选");
        require(burst.getDepthCount() == 6, "深度候选数量错误");
        require(burst.getBestDeltaUs() != null && burst.getBestDeltaUs() == 4_000L, "未选到全局最小回调差");
        RgbdFramePair burstPair = requirePair(burst.getPair(), "完整 burst 未产出帧对");
        require(burstPair.getColor().getFrameIndex() == 6, "错误选择了首个合格彩色帧");
        require(burstPair.getDepth().getFrameIndex() == 6, "错误选择了首个合格深度帧");

        System.out.printf(
                "{\"phase_delta_us\":%d,\"boundary_accepted\":true,"
                        + "\"over_boundary_rejected\":true,\"await_ms\":%d,"
                        + "\"fresh_color_index\":%d,\"fresh_depth_index\":%d,"
                        + "\"reuse_timeout_ms\":%d,\"stale_diagnostic_rejected\":true,"
                        + "\"burst_ms\":%d,\"burst_color_count\":%d,\"burst_depth_count\":%d,"
                        + "\"burst_best_delta_us\":%d,\"burst_color_index\":%d,"
                        + "\"burst_depth_index\":%d}%n",
                phasePair.getTimestampDeltaUs(),
                awaitMs,
                awaited.getColor().getFrameIndex(),
                awaited.getDepth().getFrameIndex(),
                timeoutMs,
                burstMs,
                burst.getColorCount(),
                burst.getDepthCount(),
                burst.getBestDeltaUs(),
                burstPair.getColor().getFrameIndex(),
                burstPair.getDepth().getFrameIndex());
    }

    private static VinRgbdPairer pairer(AtomicLong nowUs, long maxDeltaUs, long maxAgeUs) {
        Function0<Long> clock = nowUs::get;
        return new VinRgbdPairer(maxDeltaUs, maxAgeUs, 12, 12, clock);
    }

    private static RgbdFramePair await(
            VinRgbdPairer pairer, long minTimestampUs, long timeoutMs) throws InterruptedException {
        Function2<CoroutineScope, Continuation<? super RgbdFramePair>, Object> block =
                new Function2<>() {
                    @Override
                    public Object invoke(
                            CoroutineScope scope,
                            Continuation<? super RgbdFramePair> continuation) {
                        return pairer.awaitSnapshot(minTimestampUs, timeoutMs, continuation);
                    }
                };
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, block);
    }

    private static VinRgbdBurstResult awaitBurst(
            VinRgbdPairer pairer,
            long minTimestampUs,
            int skipColorFrames,
            int minColorFrames,
            int minDepthFrames,
            long timeoutMs) throws InterruptedException {
        Function2<CoroutineScope, Continuation<? super VinRgbdBurstResult>, Object> block =
                new Function2<>() {
                    @Override
                    public Object invoke(
                            CoroutineScope scope,
                            Continuation<? super VinRgbdBurstResult> continuation) {
                        return pairer.awaitBurst(
                                minTimestampUs,
                                skipColorFrames,
                                minColorFrames,
                                minDepthFrames,
                                timeoutMs,
                                continuation);
                    }
                };
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, block);
    }

    private static ColorFrame color(long timestampUs, int frameIndex) {
        return new ColorFrame(
                timestampUs,
                frameIndex,
                2,
                1,
                ByteBuffer.allocateDirect(6),
                "HLSD8_MJPEG",
                intrinsics(),
                null,
                2,
                1);
    }

    private static DepthFrame depth(long timestampUs, int frameIndex) {
        return new DepthFrame(
                timestampUs,
                frameIndex,
                2,
                1,
                ByteBuffer.allocateDirect(4),
                intrinsics(),
                false,
                null,
                DepthSampleFormat.DISPARITY_X8_U16);
    }

    private static CameraIntrinsics intrinsics() {
        return new CameraIntrinsics(1.0, 1.0, 1.0, 0.5, new double[5], 2, 1);
    }

    private static RgbdFramePair requirePair(RgbdFramePair pair, String message) {
        require(pair != null, message);
        return pair;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
