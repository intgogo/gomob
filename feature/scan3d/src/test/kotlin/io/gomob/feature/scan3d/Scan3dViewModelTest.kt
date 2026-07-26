package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import io.gomob.data.scan.LaserLatestScan
import io.gomob.nativebridge.berxel.BerxelDeviceInfo
import io.gomob.nativebridge.berxel.BerxelDeviceState
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Scan3dViewModelTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refreshPublishesRealLatestScan() = runTest(dispatcher) {
        val expected = LaserLatestScan(scanId = 42L, status = "done", points = 712_345)
        val source = FakeScan3dDataSource { expected }
        val vm = Scan3dViewModel(source)

        vm.refreshLatestScan()
        advanceUntilIdle()

        assertThat(vm.latestScan.value).isEqualTo(LatestScanUiState.Ready(expected))
    }

    @Test
    fun emptyAndFailureRemainDistinctAndFailureCanRetry() = runTest(dispatcher) {
        val source = FakeScan3dDataSource { null }
        val vm = Scan3dViewModel(source)

        vm.refreshLatestScan()
        advanceUntilIdle()
        assertThat(vm.latestScan.value).isEqualTo(LatestScanUiState.Empty)

        source.latestAction = { throw IOException("offline") }
        vm.refreshLatestScan()
        advanceUntilIdle()
        assertThat(vm.latestScan.value).isEqualTo(LatestScanUiState.Error)

        val recovered = LaserLatestScan(scanId = 43L, status = "done", points = null)
        source.latestAction = { recovered }
        vm.refreshLatestScan()
        advanceUntilIdle()
        assertThat(vm.latestScan.value).isEqualTo(LatestScanUiState.Ready(recovered))
    }

    @Test
    fun retryCancelsSlowRequestBeforeItCanOverwriteNewerResult() = runTest(dispatcher) {
        val first = CompletableDeferred<LaserLatestScan?>()
        val source = FakeScan3dDataSource { first.await() }
        val vm = Scan3dViewModel(source)

        vm.refreshLatestScan()
        runCurrent()

        val newer = LaserLatestScan(scanId = 99L, status = "done", points = 1_000)
        source.latestAction = { newer }
        vm.refreshLatestScan()
        advanceUntilIdle()
        first.complete(LaserLatestScan(scanId = 1L, status = "done", points = 10))
        advanceUntilIdle()

        assertThat(vm.latestScan.value).isEqualTo(LatestScanUiState.Ready(newer))
    }
}

private class FakeScan3dDataSource(
    var latestAction: suspend () -> LaserLatestScan?,
) : Scan3dDataSource {
    override val deviceState = MutableStateFlow<BerxelDeviceState>(BerxelDeviceState.Idle)
    override val lastKnownInfo = MutableStateFlow<BerxelDeviceInfo?>(null)

    override suspend fun latestScan(): LaserLatestScan? = latestAction()
}
