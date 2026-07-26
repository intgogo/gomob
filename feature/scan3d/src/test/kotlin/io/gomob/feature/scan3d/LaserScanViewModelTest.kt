package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import io.gomob.data.scan.GroundPlane
import io.gomob.data.scan.LaserCloudRenderData
import io.gomob.data.scan.LaserDoneResult
import io.gomob.data.scan.LaserPointFrame
import io.gomob.data.scan.LaserScanInfo
import io.gomob.data.scan.LaserScanResult
import io.gomob.data.scan.LaserStartResult
import io.gomob.data.scan.LaserStatusUpdate
import io.gomob.data.scan.MeasurementPoint3
import io.gomob.data.scan.MeasuredCloudArtifact
import io.gomob.data.scan.VehicleMeasurement
import io.gomob.data.scan.VehicleMeasurementOverlay
import io.gomob.data.scan.VehicleAxleMeasurement
import io.gomob.data.scan.VehicleCargoBoxMeasurement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

class LaserScanViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun connectingStopWaitsForStartResponseThenStopsRealScan() = runTest(dispatcher) {
        val startGate = CompletableDeferred<LaserStartResult>()
        val fake = FakeLaserScanDataSource(statuses = emptyList()).apply {
            startAction = { startGate.await() }
        }
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10_000)
        runCurrent()
        var exits = 0

        vm.start()
        runCurrent()
        vm.stopThen { exits++ }
        runCurrent()

        assertThat(fake.stopIds).isEmpty()
        assertThat(exits).isEqualTo(0)

        startGate.complete(LaserStartResult(42L, "session-42", "capturing"))
        advanceUntilIdle()

        assertThat(fake.stopIds).containsExactly(42L)
        assertThat(exits).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)
    }

    @Test
    fun backDuringInitialRestoreWaitsForActiveIdentityBeforeStopping() = runTest(dispatcher) {
        val activeGate = CompletableDeferred<LaserScanInfo?>()
        val fake = FakeLaserScanDataSource(statuses = emptyList()).apply {
            activeAction = { activeGate.await() }
        }
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10_000)
        var exits = 0
        runCurrent()

        vm.stopThen { exits++ }
        runCurrent()

        assertThat(fake.stopIds).isEmpty()
        assertThat(exits).isEqualTo(0)

        activeGate.complete(LaserScanInfo(88L, "scanning", "session-88", null, null))
        advanceUntilIdle()

        assertThat(fake.stopIds).containsExactly(88L)
        assertThat(exits).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)
    }

    @Test
    fun stopFailureKeepsFirstExitCallbackAndThirdRetryCanExitOnce() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(statuses = emptyList()).apply {
            stopAction = {
                if (stopCalls < 3) throw IOException("stop unavailable")
                "cancelled"
            }
            statusAction = { throw IOException("status unavailable") }
        }
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10_000)
        runCurrent()
        var exits = 0
        vm.start()
        runCurrent()

        vm.stopThen { exits++ }
        runCurrent()
        assertActiveStopError(vm, exits)

        vm.stopThen { exits += 10 }
        runCurrent()
        assertActiveStopError(vm, exits)

        vm.stopThen { exits += 100 }
        advanceUntilIdle()

        assertThat(fake.stopCalls).isEqualTo(3)
        assertThat(exits).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)
    }

    @Test
    fun bottomStopThenBackSharesOneRequestAndKeepsExitCallback() = runTest(dispatcher) {
        val stopGate = CompletableDeferred<String>()
        val fake = FakeLaserScanDataSource(statuses = emptyList()).apply {
            stopAction = { stopGate.await() }
        }
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10_000)
        runCurrent()
        var exits = 0
        vm.start()
        runCurrent()

        vm.stop()
        runCurrent()
        vm.stopThen { exits++ }
        runCurrent()

        assertThat(fake.stopCalls).isEqualTo(1)
        assertThat(vm.stopping.value).isTrue()
        assertThat(exits).isEqualTo(0)

        stopGate.complete("cancelled")
        advanceUntilIdle()

        assertThat(fake.stopCalls).isEqualTo(1)
        assertThat(exits).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)
    }

    @Test
    fun failedStopThenNaturalDoneSettlesExitWithoutLeakingToNextSession() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(statuses = emptyList()).apply {
            stopAction = { throw IOException("stop unavailable") }
            statusAction = { throw IOException("status unavailable") }
        }
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10_000)
        runCurrent()
        var exits = 0
        vm.start()
        runCurrent()
        vm.stopThen { exits++ }
        runCurrent()
        assertActiveStopError(vm, exits)

        fake.statusEvents.emit(LaserStatusUpdate("session-209", "done", 0, 0))
        runCurrent()

        assertThat(exits).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)

        fake.stopAction = null
        vm.start()
        runCurrent()
        fake.statusEvents.emit(LaserStatusUpdate("session-209", "done", 0, 0))
        runCurrent()

        assertThat(exits).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(LaserScanState.Connecting)

        // 结束第二次会话，避免测试调度器在永不终止的状态轮询上持续推进虚拟时间。
        vm.stop()
        runCurrent()
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)
    }

    private fun assertActiveStopError(vm: LaserScanViewModel, exits: Int) {
        val state = vm.state.value
        assertThat(state).isInstanceOf(LaserScanState.Error::class.java)
        assertThat((state as LaserScanState.Error).activeScan).isTrue()
        assertThat(exits).isEqualTo(0)
        assertThat(vm.stopping.value).isFalse()
    }

    @Test
    fun restPollingCompletesWhenWebSocketDoneIsLost() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(
                LaserScanInfo(209, "capturing", "session-209", null, null),
                LaserScanInfo(209, "done", "session-209", result, null),
            ),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state).isInstanceOf(LaserScanState.Completed::class.java)
        state as LaserScanState.Completed
        assertThat(state.measurement).isEqualTo(result.measurement)
        assertThat(state.points).isEqualTo(result.points)
        assertThat(state.measuredCloudVerified).isTrue()
        assertThat(vm.fusedCloud.value.pointCount).isEqualTo(result.points)
        assertThat(fake.statusCalls).isEqualTo(2)
        assertThat(fake.downloadCalls).containsExactly("fused", "measured", "unit_a", "unit_b").inOrder()
    }

    @Test
    fun unverifiedSiteResultKeepsDimensionsButIsNotProductionOrComplianceEligible() = runTest(dispatcher) {
        val verified = completedResult()
        val result = verified.copy(
            siteQualityVerified = false,
            siteQualityOverride = true,
            productionEligible = false,
            measurement = verified.measurement.copy(
                complianceDetermined = true,
                complianceReason = "不应保留",
                compliant = true,
                violations = listOf("不应展示"),
            ),
        )
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Completed
        assertThat(state.siteQualityVerified).isFalse()
        assertThat(state.siteQualityOverride).isTrue()
        assertThat(state.productionEligible).isFalse()
        assertThat(state.measurement.valid).isTrue()
        assertThat(state.measurement.lengthMm).isEqualTo(1768f)
        assertThat(state.measurement.widthMm).isEqualTo(531f)
        assertThat(state.measurement.heightMm).isEqualTo(763f)
        assertThat(state.measurement.complianceDetermined).isFalse()
        assertThat(state.measurement.complianceReason).isEqualTo("site_quality_unverified")
        assertThat(state.measurement.compliant).isFalse()
        assertThat(state.measurement.violations).isEmpty()
    }

    @Test
    fun controlledSiteOverrideDoesNotReportProductionVerificationFailure() {
        val text = siteQualityStatusText(verified = false, override = true)

        assertThat(text).contains("已受控启用")
        assertThat(text).doesNotContain("未通过生产验证")
    }

    @Test
    fun realUnverifiedSiteStillReportsProductionVerificationFailure() {
        assertThat(siteQualityStatusText(verified = false, override = false))
            .contains("未通过生产验证")
    }

    @Test
    fun restFailureExplainsBackgroundIncompatibility() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(
            statuses = listOf(
                LaserScanInfo(
                    scanId = 209,
                    status = "failed",
                    sessionKey = "session-209",
                    result = null,
                    error = "background_incompatible: region revision changed",
                ),
            ),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state).isInstanceOf(LaserScanState.Error::class.java)
        assertThat((state as LaserScanState.Error).msg).contains("背景")
        assertThat(state.msg).contains("不兼容")
        assertThat(fake.downloadCalls).isEmpty()
    }

    @Test
    fun webSocketAndRestCompletionRaceDownloadsEachCloudOnce() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            downloadDelayMs = 20,
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        runCurrent()
        assertThat(fake.done.tryEmit(LaserDoneResult(jobId = 209, result = result))).isTrue()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
        assertThat(fake.statusCalls).isEqualTo(1)
        assertThat(fake.downloadCalls).containsExactly("fused", "measured", "unit_a", "unit_b").inOrder()
    }

    @Test
    fun failedFinalCloudRetriesUntilSuccessWithoutRedownloadingSuccessfulClouds() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            downloadFailures = mutableMapOf("unit_b" to 2),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Completed
        assertThat(state.pointIntegrityWarning).isNull()
        assertThat(vm.unitBCloud.value.pointCount).isEqualTo(result.ptsB)
        assertThat(fake.downloadCalls).containsExactly(
            "fused",
            "measured",
            "unit_a",
            "unit_b",
            "unit_b",
            "unit_b",
        ).inOrder()
    }

    @Test
    fun manualRetryContinuesAfterAutomaticCloudRetriesAreExhausted() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            downloadFailures = mutableMapOf("unit_b" to 4),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        assertThat((vm.state.value as LaserScanState.Completed).pointIntegrityWarning).contains("B PCD 下载失败")
        assertThat(fake.downloadCalls.count { it == "unit_b" }).isEqualTo(4)

        vm.retryFinalClouds()
        advanceUntilIdle()

        assertThat((vm.state.value as LaserScanState.Completed).pointIntegrityWarning).isNull()
        assertThat(fake.downloadCalls.count { it == "unit_b" }).isEqualTo(5)
    }

    @Test
    fun measuredCloudMustVerifyBeforeAnyVehicleConclusionIsShown() = runTest(dispatcher) {
        val base = completedResult()
        val result = base.copy(
            measurement = base.measurement.copy(
                axle = VehicleAxleMeasurement(valid = true, numAxles = 2, wheelbasesMm = listOf(1_000f)),
                cargoBox = VehicleCargoBoxMeasurement(hasBox = true, outerLengthMm = 900f),
                overlay = VehicleMeasurementOverlay(
                    valid = true,
                    vehicleBox = listOf(MeasurementPoint3(1f, 2f, 3f)),
                    hasCargoBox = false,
                    cargoBox = emptyList(),
                    axleLines = emptyList(),
                ),
            ),
        )
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            downloadFailures = mutableMapOf("measured" to 4),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val unverified = vm.state.value as LaserScanState.Completed
        assertThat(unverified.measuredCloudVerified).isFalse()
        assertThat(unverified.measurement.valid).isFalse()
        assertThat(unverified.measurement.reason).isEqualTo("measured_cloud_unverified")
        assertThat(unverified.measurement.lengthMm).isEqualTo(0f)
        assertThat(unverified.measurement.axle.valid).isFalse()
        assertThat(unverified.measurement.cargoBox.hasBox).isFalse()
        assertThat(unverified.measurement.overlay).isNull()
        assertThat(vm.fusedCloud.value.pointCount).isEqualTo(result.points)
        assertThat(fake.downloadCalls.count { it == "measured" }).isEqualTo(4)
        assertThat(fake.downloadCalls.count { it == "fused" }).isEqualTo(1)

        vm.retryFinalClouds()
        advanceUntilIdle()

        val verified = vm.state.value as LaserScanState.Completed
        assertThat(verified.measuredCloudVerified).isTrue()
        assertThat(verified.measurement.valid).isTrue()
        assertThat(verified.measurement.lengthMm).isEqualTo(1768f)
        assertThat(verified.measurement.axle.valid).isTrue()
        assertThat(verified.measurement.cargoBox.hasBox).isTrue()
        assertThat(verified.measurement.overlay?.valid).isTrue()
    }

    @Test
    fun incompleteWebSocketDoneDoesNotBlockCompleteRestResult() = runTest(dispatcher) {
        val complete = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", complete, null)),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        runCurrent()
        fake.done.tryEmit(
            LaserDoneResult(
                jobId = 209,
                result = complete.copy(unitAObjectKey = null, unitBObjectKey = null),
            ),
        )
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
        assertThat(fake.downloadCalls).containsExactly("fused", "measured", "unit_a", "unit_b").inOrder()
    }

    @Test
    fun latestCompletedScanIsRestoredOnEntry() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = emptyList(),
            latestScan = LaserScanInfo(209, "done", "session-209", result, null),
        )

        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
        assertThat(fake.downloadCalls).containsExactly("fused", "measured", "unit_a", "unit_b").inOrder()
        assertThat(fake.statusCalls).isEqualTo(0)
    }

    @Test
    fun validMeasurementWithoutMeasuredArtifactUsesFusedOnlyAsInvalidDiagnostic() = runTest(dispatcher) {
        val unsafeResult = completedResult().copy(
            measuredObjectKey = null,
            measurement = completedResult().measurement.copy(
                valid = true,
                overlay = VehicleMeasurementOverlay(
                    valid = true,
                    vehicleBox = listOf(MeasurementPoint3(1f, 2f, 3f)),
                    hasCargoBox = false,
                    cargoBox = emptyList(),
                    axleLines = emptyList(),
                ),
            ),
        )
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", unsafeResult, null)),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Completed
        assertThat(state.measuredCloudVerified).isFalse()
        assertThat(state.measurement.valid).isFalse()
        assertThat(state.measurement.lengthMm).isEqualTo(0f)
        assertThat(state.measurement.widthMm).isEqualTo(0f)
        assertThat(state.measurement.heightMm).isEqualTo(0f)
        assertThat(state.measurement.reason).isEqualTo("measured_artifact_missing")
        assertThat(state.measurement.overlay).isNull()
        assertThat(fake.downloadCalls).containsExactly("fused", "unit_a", "unit_b").inOrder()
    }

    @Test
    fun invalidMeasurementWithBrokenArtifactAlsoUsesFusedDiagnostic() = runTest(dispatcher) {
        val unsafeResult = completedResult().copy(
            measuredArtifact = completedResult().measuredArtifact?.copy(sourcePoints = 999),
            measurement = completedResult().measurement.copy(valid = false, reason = "ground_drift"),
        )
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", unsafeResult, null)),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Completed
        assertThat(state.measuredCloudVerified).isFalse()
        assertThat(state.measurement.valid).isFalse()
        assertThat(state.measurement.reason).isEqualTo("measured_artifact_mismatch")
        assertThat(state.measurement.overlay).isNull()
        assertThat(fake.downloadCalls).containsExactly("fused", "unit_a", "unit_b").inOrder()
    }

    @Test
    fun activeScanRestoresSnapshotsThenUsesStatusPolling() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            activeScan = LaserScanInfo(209, "scanning", "session-209", null, null),
        )

        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
        assertThat(fake.activeDownloadCalls).containsExactly("unit_a", "unit_b")
        assertThat(fake.downloadCalls).containsExactly("fused", "measured", "unit_a", "unit_b").inOrder()
        assertThat(fake.statusCalls).isEqualTo(1)
    }

    @Test
    fun activeFusingIgnoresEarlyLiveDoneAndWaitsForDatabaseDone() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            activeScan = LaserScanInfo(209, "fusing", "session-209", null, null),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)

        runCurrent()
        assertThat(vm.state.value).isEqualTo(LaserScanState.Processing)
        advanceTimeBy(10)
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
    }

    @Test
    fun finalCloudsCannotBeOverwrittenByDelayedActiveSnapshots() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            activeScan = LaserScanInfo(209, "scanning", "session-209", null, null),
            downloadDelayMs = 20,
            activeCloudPointCount = 7,
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)

        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
        assertThat(vm.unitACloud.value.pointCount).isEqualTo(result.ptsA)
        assertThat(vm.unitBCloud.value.pointCount).isEqualTo(result.ptsB)
    }

    @Test
    fun lateWebSocketFramesDuringAndAfterCompletionCannotOverwriteFinalClouds() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            downloadDelayMs = 20,
        )
        val vm = LaserScanViewModel(
            repo = fake,
            doneDispatcher = dispatcher,
            statusPollIntervalMs = 10,
            pointDispatcher = dispatcher,
            elapsedRealtimeMs = { 1_000L },
        )
        runCurrent()

        vm.start()
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertThat(fake.points.tryEmit(
            LaserPointFrame("session-209", 0, floatArrayOf(9_000f, 9_000f, 9_000f), 12f),
        )).isTrue()
        runCurrent()
        advanceUntilIdle()

        assertThat(fake.points.tryEmit(
            LaserPointFrame("session-209", 0, floatArrayOf(8_000f, 8_000f, 8_000f), 13f),
        )).isTrue()
        runCurrent()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
        assertThat(vm.unitACloud.value.pointCount).isEqualTo(result.ptsA)
        assertThat(vm.unitACloud.value.renderPointCount).isEqualTo(result.ptsA)
        assertThat(vm.unitACloud.value.xyz.asList()).containsExactly(0f, 0f, 0f).inOrder()
    }

    @Test
    fun restoredSnapshotAndRealtimeDeltaSurviveFusingSnapshot() = runTest(dispatcher) {
        var now = 0L
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "scanning", "session-209", null, null)),
            activeScan = LaserScanInfo(209, "scanning", "session-209", null, null),
            downloadDelayMs = 20,
        )
        val vm = LaserScanViewModel(
            repo = fake,
            doneDispatcher = dispatcher,
            statusPollIntervalMs = 10_000,
            pointDispatcher = dispatcher,
            elapsedRealtimeMs = { now += 300; now },
        )
        runCurrent()

        assertThat(
            fake.points.tryEmit(
                LaserPointFrame(
                    "session-209",
                    0,
                    floatArrayOf(4f, 5f, 6f),
                    -170f,
                    sourcePointCount = 2,
                ),
            ),
        ).isTrue()
        runCurrent()
        advanceTimeBy(20)
        runCurrent()
        assertThat(vm.unitACloud.value.pointCount).isEqualTo(2)

        assertThat(
            fake.statusEvents.tryEmit(
                LaserStatusUpdate(
                    "session-209",
                    "fusing",
                    1,
                    0,
                    sourcePointsA = 1_094_822,
                    sourcePointsB = 952_898,
                ),
            ),
        ).isTrue()
        runCurrent()
        assertThat(vm.unitACloud.value.pointCount).isEqualTo(1_094_822)
        assertThat(vm.unitBCloud.value.pointCount).isEqualTo(952_898)

        vm.stop()
        runCurrent()
    }

    @Test
    fun samplerUsesServerCumulativeRegionCountEvenWhenPreviewFramesWereDropped() {
        val sampler = BoundedVoxelCloudSampler(capacity = 16)
        sampler.add(floatArrayOf(1f, 2f, 3f), hAngleDeg = 10f, sourcePointCount = 100)
        sampler.add(floatArrayOf(104f, 105f, 106f), hAngleDeg = 11f, sourcePointCount = 955_989)

        val cloud = sampler.snapshotRender()
        assertThat(cloud.sourcePointCount).isEqualTo(955_989)
        assertThat(cloud.renderPointCount).isEqualTo(2)
        assertThat(cloud.latestAngleDeg).isEqualTo(11f)
    }

    @Test
    fun activeLookupFailureBlocksStartingUntilRecoverySucceeds() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(
            statuses = emptyList(),
            activeError = IOException("network down"),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Error::class.java)
        vm.start()
        runCurrent()
        assertThat(fake.startCalls).isEqualTo(0)

        fake.activeError = null
        vm.restart()
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)
    }

    @Test
    fun startShowsActionableSiteQualityGateInsteadOfBareHttpStatus() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(
            statuses = emptyList(),
            startError = IOException("工位外参质量未达生产要求: 缺少 rms_error_mm"),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        advanceUntilIdle()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Error
        assertThat(state.msg).contains("工位外参质量未达生产要求")
        assertThat(state.msg).contains("ArUco 标定")
        assertThat(state.msg).doesNotContain("HTTP 409")
    }

    @Test
    fun startExplainsRegionRevisionChangeInsteadOfGenericBackgroundError() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(
            statuses = emptyList(),
            startError = IOException("background_incompatible: region_calibration_changed"),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        advanceUntilIdle()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Error
        assertThat(state.msg).contains("扫描区域版本已变化")
        assertThat(state.msg).contains("重新采集背景")
    }

    @Test
    fun startExplainsLegacyBackgroundStillExistsButNeedsRecapture() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(
            statuses = emptyList(),
            startError = IOException("background_incompatible: legacy_fused_requires_recapture"),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        advanceUntilIdle()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Error
        assertThat(state.msg).contains("旧版空工位背景仍在")
        assertThat(state.msg).contains("格式不兼容")
    }

    @Test
    fun acceptedStartWithLostResponseRecoversServerSession() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            startError = IOException("response timeout"),
            activeAfterStartError = LaserScanInfo(209, "scanning", "session-209", null, null),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        advanceUntilIdle()

        vm.start()
        advanceUntilIdle()

        assertThat(fake.startCalls).isEqualTo(1)
        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
        assertThat(fake.downloadCalls).containsExactly("fused", "measured", "unit_a", "unit_b").inOrder()
    }

    @Test
    fun latestRawResultDownloadsOnlyUnitCloudsAndKeepsExplicitReason() = runTest(dispatcher) {
        val raw = completedResult().copy(
            fusedObjectKey = null,
            measuredObjectKey = null,
            measuredArtifact = null,
            alignMethod = "raw",
            measurement = completedResult().measurement.copy(
                valid = false,
                mode = "raw",
                reason = "raw",
            ),
        )
        val fake = FakeLaserScanDataSource(
            statuses = emptyList(),
            latestScan = LaserScanInfo(209, "done", "session-209", raw, null),
        )

        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Completed
        assertThat(state.measurement.reason).isEqualTo("raw")
        assertThat(fake.downloadCalls).containsExactly("unit_a", "unit_b").inOrder()
    }

    @Test
    fun undoCancelsFormalScanThenAlignsBothUnitsToZero() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "capturing", "session-209", null, null)),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10_000)
        runCurrent()

        vm.start()
        runCurrent()
        vm.undo()
        advanceUntilIdle()

        assertThat(fake.stopCalls).isEqualTo(1)
        assertThat(fake.deviceCommandCalls).containsExactly(
            "a" to "ALIGN_ZERO",
            "b" to "ALIGN_ZERO",
        ).inOrder()
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)
    }

    @Test
    fun stopFailureKeepsSessionSoUserCanRetryCancellation() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "capturing", "session-209", null, null)),
            stopError = IOException("network down"),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10_000)
        runCurrent()

        vm.start()
        runCurrent()
        vm.stop()
        runCurrent()

        assertThat(fake.stopCalls).isEqualTo(1)
        assertThat(vm.state.value).isEqualTo(
            LaserScanState.Error(
                msg = "停止失败，服务端任务可能仍在运行，请重试：network down；当前状态=capturing",
                activeScan = true,
            ),
        )
        assertThat(vm.stopping.value).isFalse()

        fake.stopError = null
        vm.stop()
        advanceUntilIdle()

        assertThat(fake.stopCalls).isEqualTo(2)
        assertThat(vm.state.value).isEqualTo(LaserScanState.Idle)
    }

    @Test
    fun stopRacingWithDoneRestoresCompletedResultInsteadOfDroppingIt() = runTest(dispatcher) {
        val result = completedResult()
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            stopStatus = "done",
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10_000)
        runCurrent()

        vm.start()
        runCurrent()
        vm.stop()
        advanceUntilIdle()

        assertThat(vm.state.value).isInstanceOf(LaserScanState.Completed::class.java)
        assertThat(fake.downloadCalls).containsExactly("fused", "measured", "unit_a", "unit_b").inOrder()
    }

    @Test
    fun sampledFinalCloudsValidateCanonicalSourceCountsWithoutFalseWarning() = runTest(dispatcher) {
        val result = completedResult().copy(
            points = 7_000_000,
            ptsA = 3_200_000,
            ptsB = 3_800_000,
            measuredArtifact = completedResult().measuredArtifact?.copy(sourcePoints = 548_996),
            measurement = completedResult().measurement.copy(measuredPoints = 548_996),
        )
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", result, null)),
            downloadClouds = mapOf(
                "fused" to LaserCloudRenderData(
                    xyz = FloatArray(11 * 3),
                    rgb = IntArray(11) { 0x336699 },
                    sourcePointCount = 7_000_000,
                ),
                "measured" to LaserCloudRenderData(FloatArray(10 * 3), sourcePointCount = 548_996),
                "unit_a" to LaserCloudRenderData(
                    FloatArray(8 * 3),
                    rgb = IntArray(8) { 0x336699 },
                    sourcePointCount = 3_200_000,
                ),
                "unit_b" to LaserCloudRenderData(
                    FloatArray(9 * 3),
                    rgb = IntArray(9) { 0x336699 },
                    sourcePointCount = 3_800_000,
                ),
            ),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Completed
        assertThat(state.pointIntegrityWarning).isNull()
        assertThat(state.measuredCloudVerified).isTrue()
        assertThat(vm.fusedCloud.value.pointCount).isEqualTo(7_000_000)
        assertThat(vm.fusedCloud.value.renderPointCount).isEqualTo(11)
        assertThat(vm.fusedCloud.value.hasColor).isTrue()
        assertThat(vm.unitACloud.value.renderPointCount).isEqualTo(8)
        assertThat(vm.unitBCloud.value.renderPointCount).isEqualTo(9)
    }

    @Test
    fun colorlessFusedCloudIsReportedAsIntegrityFailure() = runTest(dispatcher) {
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", completedResult(), null)),
            downloadClouds = mapOf(
                "fused" to LaserCloudRenderData(FloatArray(2 * 3), sourcePointCount = 2),
            ),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Completed
        assertThat(state.pointIntegrityWarning).contains("融合 PCD 缺少颜色")
        assertThat(vm.fusedCloud.value.hasColor).isFalse()
    }

    @Test
    fun finalCloudSourceCountsMustConserveAPlusBEqualsFused() = runTest(dispatcher) {
        val inconsistent = completedResult().copy(points = 3)
        val fake = FakeLaserScanDataSource(
            statuses = listOf(LaserScanInfo(209, "done", "session-209", inconsistent, null)),
            downloadClouds = mapOf(
                "fused" to LaserCloudRenderData(
                    FloatArray(3 * 3),
                    rgb = IntArray(3),
                    sourcePointCount = 3,
                ),
            ),
        )
        val vm = LaserScanViewModel(fake, dispatcher, statusPollIntervalMs = 10)
        runCurrent()

        vm.start()
        advanceUntilIdle()

        val state = vm.state.value as LaserScanState.Completed
        assertThat(state.pointIntegrityWarning).contains("服务端点数不守恒")
        assertThat(state.pointIntegrityWarning).contains("PCD 点数不守恒")
    }

    @Test
    fun boundedVoxelSamplerKeepsLargeStreamDeterministicAndBounded() {
        fun sample(): LaserCloudRenderData {
            val sampler = BoundedVoxelCloudSampler(capacity = 1024, initialVoxelSizeMm = 25)
            val chunk = FloatArray(4096 * 3)
            repeat(250) { batch ->
                var point = 0
                while (point < 4096) {
                    val global = batch * 4096 + point
                    val base = point * 3
                    chunk[base] = (global % 20_000).toFloat()
                    chunk[base + 1] = ((global * 17) % 12_000).toFloat()
                    chunk[base + 2] = ((global * 31) % 2_500).toFloat()
                    point++
                }
                sampler.add(chunk, batch.toFloat())
            }
            return sampler.snapshotRender()
        }

        val first = sample()
        val second = sample()
        assertThat(first.sourcePointCount).isEqualTo(1_024_000)
        assertThat(first.renderPointCount).isAtMost(1024)
        assertThat(first.xyz.asList()).containsExactlyElementsIn(second.xyz.asList()).inOrder()
    }

    @Test
    fun boundedVoxelSamplerReleaseDropsBackingStorage() {
        val sampler = BoundedVoxelCloudSampler(capacity = 128)
        sampler.add(FloatArray(384) { it.toFloat() })
        assertThat(sampler.allocatedPointCapacity).isEqualTo(128)

        sampler.release()

        assertThat(sampler.allocatedPointCapacity).isEqualTo(0)
        assertThat(sampler.snapshotRender().renderPointCount).isEqualTo(0)
    }

    private fun completedResult(): LaserScanResult = LaserScanResult(
        sessionKey = "session-209",
        fusedObjectKey = "laser/209/fused.pcd",
        unitAObjectKey = "laser/209/a.pcd",
        unitBObjectKey = "laser/209/b.pcd",
        measuredObjectKey = "laser/209/measured.pcd",
        measuredArtifact = MeasuredCloudArtifact(
            xyzSha256 = "a".repeat(64),
            coordinateSchema = "unit_a_world_mm_v1",
            sourcePoints = 2,
            siteRevision = "site-209",
            regionRevision = "region-209",
            backgroundRevision = 17,
            finalBToASha256 = "b".repeat(64),
        ),
        points = 2,
        ptsA = 1,
        ptsB = 1,
        alignMethod = "site",
        siteRevision = "site-209",
        regionRevision = "region-209",
        siteQualityVerified = true,
        siteQualityOverride = false,
        productionEligible = true,
        measurement = VehicleMeasurement(
            lengthMm = 1768f,
            widthMm = 531f,
            heightMm = 763f,
            valid = true,
            compliant = true,
            violations = emptyList(),
            mode = "bg_subtract",
            backgroundSet = true,
            backgroundRevisionId = 17,
            foregroundPoints = 548996,
            measuredPoints = 2,
        ),
        ground = GroundPlane(0f, 0f, 1f, 0f, true),
    )
}

private class FakeLaserScanDataSource(
    private val statuses: List<LaserScanInfo>,
    private val downloadDelayMs: Long = 0,
    private var activeScan: LaserScanInfo? = null,
    private val latestScan: LaserScanInfo? = null,
    private val downloadFailures: MutableMap<String, Int> = mutableMapOf(),
    private val activeCloudPointCount: Int = 1,
    private val downloadClouds: Map<String, LaserCloudRenderData> = emptyMap(),
    var activeError: Throwable? = null,
    var startError: Throwable? = null,
    var stopError: Throwable? = null,
    var stopStatus: String = "cancelled",
    private val activeAfterStartError: LaserScanInfo? = null,
) : LaserScanDataSource {
    val points = MutableSharedFlow<LaserPointFrame>(extraBufferCapacity = 8)
    override val pointFrames: Flow<LaserPointFrame> = points
    val statusEvents = MutableSharedFlow<LaserStatusUpdate>(extraBufferCapacity = 8)
    override val statusUpdates: Flow<LaserStatusUpdate> = statusEvents
    val done = MutableSharedFlow<LaserDoneResult>(extraBufferCapacity = 1)
    override val doneEvents: Flow<LaserDoneResult> = done

    var statusCalls = 0
        private set
    var startCalls = 0
        private set
    var stopCalls = 0
        private set
    var startAction: (suspend () -> LaserStartResult)? = null
    var activeAction: (suspend () -> LaserScanInfo?)? = null
    var stopAction: (suspend () -> String)? = null
    var statusAction: (suspend (Long) -> LaserScanInfo)? = null
    val stopIds = mutableListOf<Long>()
    val downloadCalls = mutableListOf<String>()
    val activeDownloadCalls = mutableListOf<String>()
    val deviceCommandCalls = mutableListOf<Pair<String, String>>()

    override fun ensureRealtimeConnected() = Unit

    override suspend fun active(): LaserScanInfo? {
        activeAction?.let { return it() }
        activeError?.let { throw it }
        return activeScan
    }

    override suspend fun latest(): LaserScanInfo? = latestScan

    override suspend fun start(): LaserStartResult {
        startCalls++
        startAction?.let { return it() }
        startError?.let {
            activeScan = activeAfterStartError ?: activeScan
            throw it
        }
        return LaserStartResult(
            scanId = 209,
            sessionKey = "session-209",
            status = "capturing",
        )
    }

    override suspend fun stop(scanId: Long): String {
        stopCalls++
        stopIds += scanId
        stopAction?.let { return it() }
        stopError?.let { throw it }
        return stopStatus
    }

    override suspend fun status(scanId: Long): LaserScanInfo {
        statusAction?.let {
            statusCalls++
            return it(scanId)
        }
        check(statuses.isNotEmpty()) { "测试未配置状态响应" }
        val index = statusCalls.coerceAtMost(statuses.lastIndex)
        statusCalls++
        return statuses[index]
    }

    override suspend fun downloadCloudRenderData(
        scanId: Long,
        name: String,
        expectedArtifact: MeasuredCloudArtifact?,
    ): LaserCloudRenderData {
        downloadCalls += name
        if (downloadDelayMs > 0) delay(downloadDelayMs)
        val failuresLeft = downloadFailures[name] ?: 0
        if (failuresLeft > 0) {
            downloadFailures[name] = failuresLeft - 1
            throw IOException("$name 临时下载失败")
        }
        return downloadClouds[name] ?: run {
            val points = if (name == "fused" || name == "measured") 2 else 1
            LaserCloudRenderData(
                xyz = FloatArray(points * 3),
                rgb = if (name == "measured") null else IntArray(points) { 0x336699 },
            )
        }
    }

    override suspend fun downloadActiveCloudRenderData(name: String): LaserCloudRenderData {
        activeDownloadCalls += name
        if (downloadDelayMs > 0) delay(downloadDelayMs)
        return LaserCloudRenderData(FloatArray(activeCloudPointCount * 3))
    }

    override suspend fun deviceCommand(unit: String, cmd: String) {
        deviceCommandCalls += unit to cmd
    }

}
