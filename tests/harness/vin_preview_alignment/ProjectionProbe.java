package io.gomob.feature.scan3d;

import io.gomob.data.scan.VinPreviewCalibration;
import io.gomob.data.scan.VinPreviewCalibrationKey;
import io.gomob.data.scan.VinPreviewColorCalibration;
import io.gomob.data.scan.VinPreviewDepthCalibration;
import io.gomob.model.CameraIntrinsics;
import io.gomob.model.DepthFrame;
import io.gomob.model.DepthSampleFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** JVM 直接执行 Android 生产 Kotlin 投影器，验证固定向量、真帧覆盖率和 5fps 性能预算。 */
public final class ProjectionProbe {
    private static final int DEPTH_WIDTH = 640;
    private static final int DEPTH_HEIGHT = 128;
    private static final int PREVIEW_WIDTH = 1040;
    private static final int PREVIEW_HEIGHT = 208;
    private static final float ROI_INSET_DP = 20.0f;
    private static final float MAIN_VIEWPORT_WIDTH_DP = 411.0f;
    private static final float[] VIEWPORT_WIDTHS_DP = {360.0f, 411.0f, 432.0f};

    private ProjectionProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("需要当前 depth.yuv 路径和历史采集目录");
        }
        Path currentDepthPath = Path.of(args[0]);
        Path historyRoot = Path.of(args[1]);
        VinPreviewProjector projector = new VinPreviewProjector(calibration());
        VinPreviewRoi mainRoi = productionRoi(MAIN_VIEWPORT_WIDTH_DP);
        DepthFrame frame = readDepthFrame(currentDepthPath);

        double[][] vectors = {
            {324, 65, 279.38392092918923, 62.21943173082844},
            {344, 55, 306.56828818086933, 48.60381214126974},
            {304, 75, 252.1950535978917, 75.82674330390832},
        };
        double maxOracleError = 0.0;
        for (double[] vector : vectors) {
            VinPreviewCoordinate coordinate = projector.projectCoordinate$scan3d_debug(
                1300, (int) vector[0], (int) vector[1], 640, 128
            );
            if (coordinate == null) throw new IllegalStateException("固定向量投影为空");
            maxOracleError = Math.max(
                maxOracleError,
                Math.hypot(coordinate.getX() - vector[2], coordinate.getY() - vector[3])
            );
        }

        for (int i = 0; i < 3; i++) projector.project(frame, PREVIEW_WIDTH, PREVIEW_HEIGHT, mainRoi);
        double[] durationsMs = new double[10];
        VinProjectedDepth result = null;
        for (int i = 0; i < durationsMs.length; i++) {
            long started = System.nanoTime();
            result = projector.project(frame, PREVIEW_WIDTH, PREVIEW_HEIGHT, mainRoi);
            durationsMs[i] = (System.nanoTime() - started) / 1_000_000.0;
        }
        if (result == null) throw new IllegalStateException("真帧投影为空");
        VinDepthRoiMetrics roiMetrics = result.getRoiMetrics();
        if (roiMetrics == null) throw new IllegalStateException("真帧 ROI 指标为空");
        boolean roiReady = VinPreviewProjectorKt.vinCaptureQuality(roiMetrics) instanceof VinCaptureQuality.Ready;
        boolean syntheticReady = isReady(projector.project(uniformFrame(1300), PREVIEW_WIDTH, PREVIEW_HEIGHT, mainRoi));
        boolean syntheticNearReady = isReady(projector.project(uniformFrame(1700), PREVIEW_WIDTH, PREVIEW_HEIGHT, mainRoi));
        boolean syntheticTooFarReady = isReady(projector.project(uniformFrame(1100), PREVIEW_WIDTH, PREVIEW_HEIGHT, mainRoi));
        boolean syntheticInvalidReady = isReady(projector.project(uniformFrame(0), PREVIEW_WIDTH, PREVIEW_HEIGHT, mainRoi));
        int autoGateTriggerCount = autoGateTriggerCount(roiMetrics);
        WorkflowResult workflowResult = workflowResult();
        Arrays.sort(durationsMs);
        double total = 0.0;
        for (double duration : durationsMs) total += duration;
        double coverage = result.getCoveredPixels() / (double) (result.getWidth() * result.getHeight());
        List<SampleResult> history = projectHistory(projector, historyRoot);

        StringBuilder output = new StringBuilder(32_768);
        output.append('{');
        appendNumber(output, "max_oracle_error_px", maxOracleError);
        appendNumber(output, "valid_depth_points", result.getValidDepthPoints());
        appendNumber(output, "points_in_color_view", result.getPointsInColorView());
        appendNumber(output, "coverage_ratio", coverage);
        appendNumber(output, "roi_viewport_width_dp", MAIN_VIEWPORT_WIDTH_DP);
        appendNumber(output, "roi_coverage_ratio", roiMetrics.getCoverageRatio());
        appendNumber(output, "roi_projected_points", roiMetrics.getProjectedPoints());
        appendNumber(output, "roi_projected_point_ratio", roiMetrics.getProjectedPointRatio());
        appendNullableNumber(output, "roi_distance_p10_mm", roiMetrics.getDistanceP10Mm());
        appendNullableNumber(output, "roi_distance_median_mm", roiMetrics.getDistanceMedianMm());
        appendNumber(output, "roi_far_enough_ratio", roiMetrics.getFarEnoughRatio());
        appendBoolean(output, "roi_ready", roiReady);
        appendBoolean(output, "synthetic_ready", syntheticReady);
        appendBoolean(output, "synthetic_near_ready", syntheticNearReady);
        appendBoolean(output, "synthetic_too_far_ready", syntheticTooFarReady);
        appendBoolean(output, "synthetic_invalid_ready", syntheticInvalidReady);
        appendNumber(output, "min_roi_coverage", VinPreviewProjectorKt.VIN_CAPTURE_MIN_ROI_COVERAGE);
        appendNumber(
            output,
            "min_projected_point_ratio",
            VinPreviewProjectorKt.VIN_CAPTURE_MIN_ROI_PROJECTED_POINT_RATIO
        );
        appendNumber(output, "guidance_distance_mm", VinPreviewProjectorKt.VIN_GUIDANCE_DISTANCE_MM);
        appendNumber(output, "max_capture_distance_mm", VinPreviewProjectorKt.VIN_CAPTURE_MAX_DISTANCE_MM);
        appendNumber(output, "auto_gate_trigger_count", autoGateTriggerCount);
        appendNumber(output, "auto_capture_claim_count", workflowResult.captureClaimCount);
        appendNumber(output, "auto_recognition_claim_count", workflowResult.recognitionClaimCount);
        appendBoolean(output, "auto_rearmed_after_transient_quality", workflowResult.rearmedAfterTransientQuality);
        appendNumber(output, "mean_ms", total / durationsMs.length);
        appendNumber(output, "p95_ms", durationsMs[durationsMs.length - 1]);
        appendString(output, "current_capture", currentDepthPath.getParent().getFileName().toString());
        appendRaw(output, "roi_contract", roiContractJson());
        appendRaw(output, "historical_samples", historicalSamplesJson(history), false);
        output.append('}');
        System.out.println(output);
    }

    private static List<SampleResult> projectHistory(VinPreviewProjector projector, Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<Path> captures;
        try (Stream<Path> paths = Files.list(root)) {
            captures = paths
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith("cap_"))
                .filter(path -> Files.isRegularFile(path.resolve("depth.yuv")))
                .filter(path -> Files.isRegularFile(path.resolve("restore.json")))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
        List<SampleResult> results = new ArrayList<>(captures.size());
        for (Path capture : captures) {
            DepthFrame frame = readDepthFrame(capture.resolve("depth.yuv"));
            List<RoiResult> roiResults = new ArrayList<>(VIEWPORT_WIDTHS_DP.length);
            for (float widthDp : VIEWPORT_WIDTHS_DP) {
                VinPreviewRoi roi = productionRoi(widthDp);
                VinProjectedDepth projected = projector.project(frame, PREVIEW_WIDTH, PREVIEW_HEIGHT, roi);
                if (projected == null || projected.getRoiMetrics() == null) {
                    throw new IllegalStateException("历史帧投影缺少 ROI 指标: " + capture);
                }
                VinDepthRoiMetrics metrics = projected.getRoiMetrics();
                boolean ready = VinPreviewProjectorKt.vinCaptureQuality(metrics) instanceof VinCaptureQuality.Ready;
                roiResults.add(new RoiResult(widthDp, roi, metrics, ready));
            }
            results.add(new SampleResult(capture.getFileName().toString(), roiResults));
        }
        return results;
    }

    private static VinPreviewRoi productionRoi(float viewportWidthDp) {
        VinPreviewRoi roi = VinPreviewGeometryKt.vinPreviewRoi(
            viewportWidthDp,
            viewportWidthDp / VinPreviewGeometryKt.VINCREATOR_VIEWPORT_ASPECT,
            VinPreviewGeometryKt.VINCREATOR_STREAM_ASPECT,
            ROI_INSET_DP
        );
        if (roi == null) throw new IllegalStateException("生产 ROI 为空: " + viewportWidthDp + "dp");
        return roi;
    }

    private static DepthFrame readDepthFrame(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        int expected = DEPTH_WIDTH * DEPTH_HEIGHT * 2;
        if (bytes.length != expected) throw new IllegalArgumentException("深度帧大小错误: " + bytes.length);
        ByteBuffer depthBytes = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        depthBytes.put(bytes).flip();
        return new DepthFrame(
            1L,
            1,
            DEPTH_WIDTH,
            DEPTH_HEIGHT,
            depthBytes,
            new CameraIntrinsics(
                614.60498046875,
                614.60498046875,
                324.0,
                65.43250274658203,
                new double[0],
                DEPTH_WIDTH,
                DEPTH_HEIGHT
            ),
            false,
            null,
            DepthSampleFormat.DISPARITY_X8_U16
        );
    }

    private static boolean isReady(VinProjectedDepth projected) {
        if (projected == null || projected.getRoiMetrics() == null) return false;
        return VinPreviewProjectorKt.vinCaptureQuality(projected.getRoiMetrics()) instanceof VinCaptureQuality.Ready;
    }

    private static int autoGateTriggerCount(VinDepthRoiMetrics baseMetrics) {
        VinAutoCaptureGate gate = new VinAutoCaptureGate();
        int triggers = 0;
        for (int index = 0; index < 6; index++) {
            double distance = baseMetrics.getDistanceMedianMm() == null
                ? 300.0
                : baseMetrics.getDistanceMedianMm() + Math.min(index, 4);
            VinDepthRoiMetrics metrics = new VinDepthRoiMetrics(
                baseMetrics.getTotalPixels(),
                baseMetrics.getValidPixels(),
                baseMetrics.getCoverageRatio(),
                baseMetrics.getProjectedPoints(),
                baseMetrics.getProjectedPointRatio(),
                baseMetrics.getDistanceP10Mm(),
                distance,
                baseMetrics.getFarEnoughRatio()
            );
            long timestampUs = 1_000_000L + index * 200_000L;
            VinAutoCaptureDecision decision = gate.observe(
                new VinAutoCaptureObservation(
                    timestampUs,
                    timestampUs + 50_000L,
                    new VinCaptureQuality.Ready(metrics)
                )
            );
            if (decision == VinAutoCaptureDecision.Trigger.INSTANCE) triggers++;
        }
        return triggers;
    }

    private static WorkflowResult workflowResult() {
        VinAutoCaptureWorkflow workflow = new VinAutoCaptureWorkflow();
        int captureClaims = 0;
        if (workflow.tryStartCapture(VinCaptureOrigin.Auto)) captureClaims++;
        if (workflow.tryStartCapture(VinCaptureOrigin.Manual)) captureClaims++;
        int recognitionClaims = 0;
        if (workflow.onRestoreSuccess()) recognitionClaims++;
        if (workflow.onRestoreSuccess()) recognitionClaims++;

        VinAutoCaptureWorkflow transientWorkflow = new VinAutoCaptureWorkflow();
        boolean first = transientWorkflow.tryStartCapture(VinCaptureOrigin.Auto);
        transientWorkflow.rearmAfterTransientQualityFailure();
        boolean second = transientWorkflow.tryStartCapture(VinCaptureOrigin.Auto);
        return new WorkflowResult(captureClaims, recognitionClaims, first && second);
    }

    private static DepthFrame uniformFrame(int rawDisparityX8) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(DEPTH_WIDTH * DEPTH_HEIGHT * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < DEPTH_WIDTH * DEPTH_HEIGHT; i++) bytes.putShort((short) rawDisparityX8);
        bytes.flip();
        return new DepthFrame(
            1L,
            1,
            DEPTH_WIDTH,
            DEPTH_HEIGHT,
            bytes,
            new CameraIntrinsics(
                614.60498046875,
                614.60498046875,
                324.0,
                65.43250274658203,
                new double[0],
                DEPTH_WIDTH,
                DEPTH_HEIGHT
            ),
            false,
            null,
            DepthSampleFormat.DISPARITY_X8_U16
        );
    }

    private static String roiContractJson() {
        StringBuilder output = new StringBuilder(512).append('[');
        for (int index = 0; index < VIEWPORT_WIDTHS_DP.length; index++) {
            if (index > 0) output.append(',');
            float widthDp = VIEWPORT_WIDTHS_DP[index];
            output.append(roiJson(widthDp, productionRoi(widthDp)));
        }
        return output.append(']').toString();
    }

    private static String historicalSamplesJson(List<SampleResult> samples) {
        StringBuilder output = new StringBuilder(24_000).append('[');
        for (int sampleIndex = 0; sampleIndex < samples.size(); sampleIndex++) {
            if (sampleIndex > 0) output.append(',');
            SampleResult sample = samples.get(sampleIndex);
            output.append("{\"capture\":").append(jsonString(sample.capture)).append(",\"roi_metrics\":[");
            for (int roiIndex = 0; roiIndex < sample.roiResults.size(); roiIndex++) {
                if (roiIndex > 0) output.append(',');
                output.append(roiMetricsJson(sample.roiResults.get(roiIndex)));
            }
            output.append("]}");
        }
        return output.append(']').toString();
    }

    private static String roiMetricsJson(RoiResult result) {
        VinDepthRoiMetrics metrics = result.metrics;
        StringBuilder output = new StringBuilder(512).append('{');
        appendNumber(output, "viewport_width_dp", result.viewportWidthDp);
        appendRaw(output, "normalized_roi", normalizedRoiJson(result.roi));
        appendNumber(output, "total_pixels", metrics.getTotalPixels());
        appendNumber(output, "valid_pixels", metrics.getValidPixels());
        appendNumber(output, "coverage_ratio", metrics.getCoverageRatio());
        appendNumber(output, "projected_points", metrics.getProjectedPoints());
        appendNumber(output, "projected_point_ratio", metrics.getProjectedPointRatio());
        appendNullableNumber(output, "distance_p10_mm", metrics.getDistanceP10Mm());
        appendNullableNumber(output, "distance_median_mm", metrics.getDistanceMedianMm());
        appendNumber(output, "far_enough_ratio", metrics.getFarEnoughRatio());
        appendBoolean(output, "ready", result.ready, false);
        return output.append('}').toString();
    }

    private static String roiJson(float widthDp, VinPreviewRoi roi) {
        StringBuilder output = new StringBuilder(192).append('{');
        appendNumber(output, "viewport_width_dp", widthDp);
        appendRaw(output, "normalized_roi", normalizedRoiJson(roi), false);
        return output.append('}').toString();
    }

    private static String normalizedRoiJson(VinPreviewRoi roi) {
        return String.format(
            Locale.ROOT,
            "{\"left\":%.12g,\"top\":%.12g,\"right\":%.12g,\"bottom\":%.12g}",
            roi.getLeft(),
            roi.getTop(),
            roi.getRight(),
            roi.getBottom()
        );
    }

    private static void appendNumber(StringBuilder output, String name, double value) {
        appendNumber(output, name, value, true);
    }

    private static void appendNumber(StringBuilder output, String name, double value, boolean comma) {
        output.append(jsonString(name)).append(':').append(number(value));
        if (comma) output.append(',');
    }

    private static void appendNullableNumber(StringBuilder output, String name, Double value) {
        output.append(jsonString(name)).append(':').append(value == null ? "null" : number(value)).append(',');
    }

    private static void appendBoolean(StringBuilder output, String name, boolean value) {
        appendBoolean(output, name, value, true);
    }

    private static void appendBoolean(StringBuilder output, String name, boolean value, boolean comma) {
        output.append(jsonString(name)).append(':').append(value);
        if (comma) output.append(',');
    }

    private static void appendString(StringBuilder output, String name, String value) {
        output.append(jsonString(name)).append(':').append(jsonString(value)).append(',');
    }

    private static void appendRaw(StringBuilder output, String name, String value) {
        appendRaw(output, name, value, true);
    }

    private static void appendRaw(StringBuilder output, String name, String value, boolean comma) {
        output.append(jsonString(name)).append(':').append(value);
        if (comma) output.append(',');
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "null";
        return String.format(Locale.ROOT, "%.12g", value);
    }

    private static String jsonString(String value) {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\"";
    }

    private static final class RoiResult {
        final float viewportWidthDp;
        final VinPreviewRoi roi;
        final VinDepthRoiMetrics metrics;
        final boolean ready;

        RoiResult(float viewportWidthDp, VinPreviewRoi roi, VinDepthRoiMetrics metrics, boolean ready) {
            this.viewportWidthDp = viewportWidthDp;
            this.roi = roi;
            this.metrics = metrics;
            this.ready = ready;
        }
    }

    private static final class WorkflowResult {
        final int captureClaimCount;
        final int recognitionClaimCount;
        final boolean rearmedAfterTransientQuality;

        WorkflowResult(int captureClaimCount, int recognitionClaimCount, boolean rearmedAfterTransientQuality) {
            this.captureClaimCount = captureClaimCount;
            this.recognitionClaimCount = recognitionClaimCount;
            this.rearmedAfterTransientQuality = rearmedAfterTransientQuality;
        }
    }

    private static final class SampleResult {
        final String capture;
        final List<RoiResult> roiResults;

        SampleResult(String capture, List<RoiResult> roiResults) {
            this.capture = capture;
            this.roiResults = roiResults;
        }
    }

    private static VinPreviewCalibration calibration() {
        return new VinPreviewCalibration(
            1,
            "vincreator_factory_v3",
            "absolute_camera_z",
            new VinPreviewCalibrationKey("BF301208", "202303111518", 640, 128, 4160, 832),
            "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2",
            3,
            new VinPreviewDepthCalibration(
                324.0,
                65.43250274658203,
                614.60498046875,
                614.60498046875,
                1229.2099609375,
                49.98929977416992,
                0.125,
                50.0,
                1000.0
            ),
            new VinPreviewColorCalibration(
                1274.610937612,
                2119.555128713,
                5737.022753971,
                5642.090890116,
                Arrays.asList(
                    1.282934287418e-08,
                    1.624058936172e-05,
                    4.424457479974e-07,
                    -1.42047938331e-05,
                    -1.777752630382e-07
                ),
                Arrays.asList(
                    0.988181353727503, -0.001554393417785, 0.153281427467200,
                    0.000002789863576, -0.999948403706616, -0.010158243785565,
                    0.153289308620975, 0.010038614729785, -0.988130362895914
                ),
                Arrays.asList(1.475623094293, 24.99666656691, -8.735002017036)
            )
        );
    }
}
