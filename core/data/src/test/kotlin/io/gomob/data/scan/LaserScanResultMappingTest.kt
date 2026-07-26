package io.gomob.data.scan

import com.google.common.truth.Truth.assertThat
import io.gomob.network.ApiException
import io.gomob.network.LaserScanStatusResponse
import io.gomob.network.LaserMeasuredCloudArtifact
import io.gomob.network.LaserVehicleOverlay
import io.gomob.realtime.RealtimeEvent
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class LaserScanResultMappingTest {
    private val parityJson = Json { ignoreUnknownKeys = true }
    private val measuredArtifact = LaserMeasuredCloudArtifact(
        xyzSha256 = "a".repeat(64),
        coordinateSchema = "unit_a_world_mm_v1",
        sourcePoints = 530001,
        siteRevision = "site-sha-209",
        regionRevision = "region-sha-209",
        backgroundRevision = 17,
        finalBToASha256 = "b".repeat(64),
    )
    private val overlay = LaserVehicleOverlay(
        valid = true,
        vehicleBox = (0 until 8).map { i -> listOf(1f + i, 2f + i, 3f + i) },
        hasCargoBox = true,
        cargoBox = (0 until 8).map { i -> listOf(7f + i, 8f + i, 9f + i) },
        axleLines = listOf(listOf(listOf(10f, 11f, 12f), listOf(13f, 14f, 15f))),
    )

    @Test
    fun webSocketAndRestMapToTheSameDomainResult() {
        val ws = RealtimeEvent.LaserScanDone(
            jobId = 209,
            sessionKey = "laser-session-209",
            fusedObjectKey = "laser/209/fused.pcd",
            unitAObjectKey = "laser/209/a.pcd",
            unitBObjectKey = "laser/209/b.pcd",
            measuredObjectKey = "laser/209/measured.pcd",
            measuredArtifact = measuredArtifact,
            points = 548996,
            ptsA = 1049840,
            ptsB = 1000000,
            alignMethod = "site",
            siteRevision = "site-sha-209",
            regionRevision = "region-sha-209",
            siteQualityVerified = true,
            siteQualityOverride = false,
            productionEligible = true,
            lengthMm = 1768f,
            widthMm = 531f,
            heightMm = 763f,
            measureValid = true,
            complianceDetermined = true,
            complianceReason = "rule-gb7258-v1",
            compliant = false,
            violations = listOf("车宽超限"),
            measMode = "BG_SUBTRACT",
            backgroundSet = true,
            backgroundCompatible = true,
            backgroundReason = "ready",
            backgroundRevisionId = 17,
            backgroundSchema = "raw_unit_frames_v1",
            foregroundPoints = 548996,
            measuredPoints = 530001,
            numAxles = 3,
            wheelbasesMm = listOf(1450f, 1460f),
            totalWheelbaseMm = 2910f,
            frontOverhangMm = 930f,
            rearOverhangMm = 1040f,
            axleValid = true,
            hasCargoBox = true,
            boxOuterLengthMm = 3100f,
            boxOuterWidthMm = 2050f,
            boxDepthMm = 620f,
            boxInnerWidthMm = 1980f,
            overlay = overlay,
            groundNx = 0.01f,
            groundNy = -0.02f,
            groundNz = 0.99f,
            groundD = -123f,
            groundValid = true,
        )
        val rest = LaserScanStatusResponse(
            scanId = 209,
            sessionKey = "laser-session-209",
            status = "DONE",
            alignMethod = "site",
            siteRevision = "site-sha-209",
            regionRevision = "region-sha-209",
            siteQualityVerified = true,
            siteQualityOverride = false,
            productionEligible = true,
            points = 548996,
            ptsA = 1049840,
            ptsB = 1000000,
            resultObjectKey = "laser/209/fused.pcd",
            unitAObjectKey = "laser/209/a.pcd",
            unitBObjectKey = "laser/209/b.pcd",
            measuredObjectKey = "laser/209/measured.pcd",
            measuredArtifact = measuredArtifact,
            measMode = "BG_SUBTRACT",
            backgroundSet = true,
            backgroundCompatible = true,
            backgroundReason = "ready",
            backgroundRevisionId = 17,
            backgroundSchema = "raw_unit_frames_v1",
            foregroundPoints = 548996,
            measuredPoints = 530001,
            lengthMm = 1768f,
            widthMm = 531f,
            heightMm = 763f,
            measureValid = true,
            complianceDetermined = true,
            complianceReason = "rule-gb7258-v1",
            compliant = false,
            violations = listOf("车宽超限"),
            numAxles = 3,
            wheelbasesMm = listOf(1450f, 1460f),
            totalWheelbaseMm = 2910f,
            frontOverhangMm = 930f,
            rearOverhangMm = 1040f,
            axleValid = true,
            hasCargoBox = true,
            boxOuterLengthMm = 3100f,
            boxOuterWidthMm = 2050f,
            boxDepthMm = 620f,
            boxInnerWidthMm = 1980f,
            overlay = overlay,
            groundNx = 0.01f,
            groundNy = -0.02f,
            groundNz = 0.99f,
            groundD = -123f,
            groundValid = true,
        )

        val wsResult = ws.toDomainResult()
        val restInfo = rest.toDomainInfo()

        assertThat(restInfo.status).isEqualTo("done")
        assertThat(restInfo.result).isEqualTo(wsResult)
        assertThat(wsResult.measurement.complianceDetermined).isTrue()
        assertThat(wsResult.measurement.complianceReason).isEqualTo("rule-gb7258-v1")
        assertThat(wsResult.measurement.mode).isEqualTo("bg_subtract")
        assertThat(wsResult.measuredObjectKey).isEqualTo("laser/209/measured.pcd")
        assertThat(wsResult.siteRevision).isEqualTo("site-sha-209")
        assertThat(wsResult.regionRevision).isEqualTo("region-sha-209")
        assertThat(wsResult.siteQualityVerified).isTrue()
        assertThat(wsResult.siteQualityOverride).isFalse()
        assertThat(wsResult.productionEligible).isTrue()
        assertThat(wsResult.measurement.backgroundCompatible).isTrue()
        assertThat(wsResult.measurement.backgroundReason).isEqualTo("ready")
        assertThat(wsResult.measurement.measuredPoints).isEqualTo(530001)
        assertThat(wsResult.measurement.axle.wheelbasesMm).containsExactly(1450f, 1460f).inOrder()
        assertThat(wsResult.measurement.cargoBox.hasBox).isTrue()
        assertThat(wsResult.measurement.overlay?.vehicleBox).hasSize(8)
        assertThat(wsResult.measurement.overlay?.vehicleBox?.first()).isEqualTo(MeasurementPoint3(1f, 2f, 3f))
        assertThat(wsResult.measurement.overlay?.axleLines).containsExactly(
            MeasurementLine3(
                MeasurementPoint3(10f, 11f, 12f),
                MeasurementPoint3(13f, 14f, 15f),
            ),
        )
    }

    @Test
    fun unverifiedSiteKeepsMeasuredDimensionsButClearsComplianceForWebSocketAndRest() {
        val wsResult = RealtimeEvent.LaserScanDone(
            jobId = 209,
            sessionKey = "laser-session-209",
            fusedObjectKey = "laser/209/fused.pcd",
            unitAObjectKey = "laser/209/a.pcd",
            unitBObjectKey = "laser/209/b.pcd",
            measuredObjectKey = "laser/209/measured.pcd",
            measuredArtifact = measuredArtifact,
            points = 548996,
            ptsA = 1049840,
            ptsB = 1000000,
            alignMethod = "site",
            siteRevision = "site-sha-209",
            regionRevision = "region-sha-209",
            siteQualityVerified = false,
            siteQualityOverride = true,
            productionEligible = false,
            lengthMm = 1768f,
            widthMm = 531f,
            heightMm = 763f,
            measureValid = true,
            complianceDetermined = true,
            complianceReason = "不应保留",
            compliant = true,
            violations = listOf("不应展示"),
            backgroundRevisionId = 17,
            measuredPoints = 530001,
        ).toDomainResult()
        val restResult = requireNotNull(
            LaserScanStatusResponse(
                scanId = 209,
                sessionKey = "laser-session-209",
                status = "done",
                alignMethod = "site",
                siteRevision = "site-sha-209",
                regionRevision = "region-sha-209",
                siteQualityVerified = false,
                siteQualityOverride = true,
                productionEligible = false,
                resultObjectKey = "laser/209/fused.pcd",
                unitAObjectKey = "laser/209/a.pcd",
                unitBObjectKey = "laser/209/b.pcd",
                measuredObjectKey = "laser/209/measured.pcd",
                measuredArtifact = measuredArtifact,
                lengthMm = 1768f,
                widthMm = 531f,
                heightMm = 763f,
                measureValid = true,
                complianceDetermined = true,
                complianceReason = "不应保留",
                compliant = true,
                violations = listOf("不应展示"),
                backgroundRevisionId = 17,
                measuredPoints = 530001,
            ).toDomainInfo().result,
        )

        listOf(wsResult, restResult).forEach { result ->
            assertThat(result.siteQualityVerified).isFalse()
            assertThat(result.siteQualityOverride).isTrue()
            assertThat(result.productionEligible).isFalse()
            assertThat(result.measurement.valid).isTrue()
            assertThat(result.measurement.lengthMm).isEqualTo(1768f)
            assertThat(result.measurement.widthMm).isEqualTo(531f)
            assertThat(result.measurement.heightMm).isEqualTo(763f)
            assertThat(result.measurement.complianceDetermined).isFalse()
            assertThat(result.measurement.complianceReason).isEqualTo("site_quality_unverified")
            assertThat(result.measurement.compliant).isFalse()
            assertThat(result.measurement.violations).isEmpty()
        }
    }

    @Test
    fun validMeasurementWithoutMeasuredArtifactFailsClosedForWebSocketAndRest() {
        val wsResult = minimalDone(overlay).copy(
            measureValid = true,
            compliant = true,
            violations = listOf("不应展示"),
            lengthMm = 4_800f,
            widthMm = 1_900f,
            heightMm = 2_100f,
            axleValid = true,
            hasCargoBox = true,
        ).toDomainResult()
        val restResult = requireNotNull(
            LaserScanStatusResponse(
                scanId = 209,
                sessionKey = "s",
                status = "done",
                alignMethod = "site",
                resultObjectKey = "f",
                unitAObjectKey = "a",
                unitBObjectKey = "b",
                measureValid = true,
                compliant = true,
                violations = listOf("不应展示"),
                lengthMm = 4_800f,
                widthMm = 1_900f,
                heightMm = 2_100f,
                axleValid = true,
                hasCargoBox = true,
                overlay = overlay,
            ).toDomainInfo().result,
        )

        listOf(wsResult, restResult).forEach { result ->
            assertThat(result.fusedObjectKey).isEqualTo("f")
            assertThat(result.measuredObjectKey).isNull()
            assertThat(result.measurement.valid).isFalse()
            assertThat(result.measurement.compliant).isFalse()
            assertThat(result.measurement.lengthMm).isEqualTo(0f)
            assertThat(result.measurement.widthMm).isEqualTo(0f)
            assertThat(result.measurement.heightMm).isEqualTo(0f)
            assertThat(result.measurement.violations).isEmpty()
            assertThat(result.measurement.reason).isEqualTo("measured_artifact_missing")
            assertThat(result.measurement.axle.valid).isFalse()
            assertThat(result.measurement.cargoBox.hasBox).isFalse()
            assertThat(result.measurement.overlay).isNull()
        }
    }

    @Test
    fun invalidMeasurementWithBrokenArtifactClearsMeasuredGeometry() {
        val result = minimalDone(overlay).copy(
            measuredObjectKey = "laser/209/measured.pcd",
            measuredArtifact = measuredArtifact.copy(sourcePoints = 999),
            siteRevision = "site-sha-209",
            regionRevision = "region-sha-209",
            backgroundRevisionId = 17,
            measuredPoints = 530001,
            measureValid = false,
            measureReason = "ground_drift",
            compliant = true,
            violations = listOf("不应展示"),
            axleValid = true,
            hasCargoBox = true,
        ).toDomainResult()

        assertThat(result.fusedObjectKey).isEqualTo("f")
        assertThat(result.measuredObjectKey).isNull()
        assertThat(result.measuredArtifact).isNull()
        assertThat(result.measurement.valid).isFalse()
        assertThat(result.measurement.reason).isEqualTo("measured_artifact_mismatch")
        assertThat(result.measurement.compliant).isFalse()
        assertThat(result.measurement.violations).isEmpty()
        assertThat(result.measurement.axle.valid).isFalse()
        assertThat(result.measurement.cargoBox.hasBox).isFalse()
        assertThat(result.measurement.overlay).isNull()
    }

    @Test
    fun invalidMeasurementReasonPrefersServerThenDerivesKnownModes() {
        assertThat(
            resolveMeasurementReason(
                serverReason = " REGION_MISSING ",
                mode = "no_isolation",
                alignMethod = "site",
                valid = false,
                backgroundCaptured = false,
                backgroundIncompatible = false,
            ),
        ).isEqualTo("region_missing")
        assertThat(inferredReason("background_incompatible_revision")).isEqualTo("background_incompatible")
        assertThat(inferredReason("region_missing")).isEqualTo("region_missing")
        assertThat(inferredReason("no_isolation")).isEqualTo("no_isolation")
        assertThat(inferredReason("raw", alignMethod = "raw")).isEqualTo("raw")
        assertThat(inferredReason("unknown")).isEqualTo("measurement_invalid")
    }

    @Test
    fun invalidOverlayCoordinatesAreDiscardedWithoutInventingGeometry() {
        val result = minimalDone(
            overlay = LaserVehicleOverlay(
                valid = true,
                vehicleBox = listOf(listOf(1f, 2f), listOf(3f, 4f, 5f)),
                axleLines = listOf(
                    listOf(listOf(1f, 2f, 3f)),
                    listOf(listOf(4f, 5f, 6f), listOf(7f, 8f, 9f)),
                ),
            ),
        ).copy(
            measuredObjectKey = "laser/209/measured.pcd",
            measuredArtifact = measuredArtifact,
            siteRevision = "site-sha-209",
            regionRevision = "region-sha-209",
            backgroundRevisionId = 17,
            measuredPoints = 530001,
            measureValid = true,
        ).toDomainResult()

        assertThat(result.measurement.overlay?.valid).isFalse()
        assertThat(result.measurement.overlay?.vehicleBox).isEmpty()
        assertThat(result.measurement.overlay?.axleLines).isEmpty()
    }

    @Test
    fun activeLiveDoneCannotSkipTheServerMeasurementPhase() {
        val response = LaserScanStatusResponse(
            scanId = 209,
            sessionKey = "s",
            status = "fusing",
            active = true,
            liveState = "done",
            siteQualityVerified = false,
            siteQualityOverride = true,
            productionEligible = false,
        )

        assertThat(response.resolveActiveStatus()).isEqualTo("fusing")
        val info = response.toDomainInfo(statusOverride = response.resolveActiveStatus())
        assertThat(info.result).isNull()
        assertThat(info.siteQualityVerified).isFalse()
        assertThat(info.siteQualityOverride).isTrue()
        assertThat(info.productionEligible).isFalse()
    }

    @Test
    fun malformedStatusCannotBeSilentlyAcceptedAsScanZero() {
        val malformed = LaserScanStatusResponse(active = true)

        assertThrows(ApiException::class.java) {
            malformed.requireValidScanIdentity(endpoint = "active")
        }
    }

    @Test
    fun harnessOutputsCanonicalResultFromRealRestMapper() {
        val fixturePath = System.getenv("GOMOB_PARITY_FIXTURE").orEmpty()
        val outputPath = System.getenv("GOMOB_PARITY_OUTPUT").orEmpty()
        assumeTrue("仅由 laser_app_web_parity harness 启用", fixturePath.isNotBlank() && outputPath.isNotBlank())

        val response = parityJson.decodeFromString<LaserScanStatusResponse>(File(fixturePath).readText())
        val result = requireNotNull(response.toDomainInfo().result)
        File(outputPath).writeText(appParityRecord(result).toString())
    }

    private fun inferredReason(mode: String, alignMethod: String = "site"): String? = resolveMeasurementReason(
        serverReason = null,
        mode = mode,
        alignMethod = alignMethod,
        valid = false,
        backgroundCaptured = false,
        backgroundIncompatible = false,
    )

    private fun minimalDone(overlay: LaserVehicleOverlay): RealtimeEvent.LaserScanDone =
        RealtimeEvent.LaserScanDone(
            jobId = 1,
            sessionKey = "s",
            fusedObjectKey = "f",
            unitAObjectKey = "a",
            unitBObjectKey = "b",
            points = 1,
            ptsA = 1,
            ptsB = 1,
            alignMethod = "site",
            lengthMm = 0f,
            widthMm = 0f,
            heightMm = 0f,
            measureValid = false,
            compliant = false,
            violations = emptyList(),
            overlay = overlay,
        )

    private fun appParityRecord(result: LaserScanResult) = buildJsonObject {
        put("client", "app")
        putJsonObject("effective") {
            put("site_revision", result.siteRevision.orEmpty())
            put("region_revision", result.regionRevision.orEmpty())
            put("background_revision", result.measurement.backgroundRevisionId ?: 0)
        }
        putJsonObject("result") {
            put("session_key", result.sessionKey)
            put("result_object_key", result.fusedObjectKey.orEmpty())
            put("unit_a_object_key", result.unitAObjectKey.orEmpty())
            put("unit_b_object_key", result.unitBObjectKey.orEmpty())
            put("measured_object_key", result.measuredObjectKey.orEmpty())
            put("points", result.points)
            put("pts_a", result.ptsA)
            put("pts_b", result.ptsB)
            put("align_method", result.alignMethod)
            put("site_revision", result.siteRevision.orEmpty())
            put("region_revision", result.regionRevision.orEmpty())
            put("measure_mode", result.measurement.mode)
            put("measure_valid", result.measurement.valid)
            put("measure_reason", result.measurement.reason.orEmpty())
            put("background_captured", result.measurement.backgroundCaptured)
            put("length_mm", result.measurement.lengthMm)
            put("width_mm", result.measurement.widthMm)
            put("height_mm", result.measurement.heightMm)
            put("compliance_determined", result.measurement.complianceDetermined)
            put("compliance_reason", result.measurement.complianceReason.orEmpty())
            put("compliant", result.measurement.compliant)
            putJsonArray("violations") {
                result.measurement.violations.forEach { add(it) }
            }
            put("background_set", result.measurement.backgroundSet)
            put("background_compatible", result.measurement.backgroundCompatible == true)
            put("background_incompatible", result.measurement.backgroundIncompatible)
            put("background_reason", result.measurement.backgroundReason.orEmpty())
            put("background_revision_id", result.measurement.backgroundRevisionId ?: 0)
            put("background_schema", result.measurement.backgroundSchema.orEmpty())
            put("fg_points", result.measurement.foregroundPoints)
            put("measured_points", result.measurement.measuredPoints)
            putJsonObject("measured_artifact") {
                val artifact = requireNotNull(result.measuredArtifact)
                put("xyz_sha256", artifact.xyzSha256)
                put("coordinate_schema", artifact.coordinateSchema)
                put("source_points", artifact.sourcePoints)
                put("site_revision", artifact.siteRevision.orEmpty())
                put("region_revision", artifact.regionRevision.orEmpty())
                put("background_revision", artifact.backgroundRevision ?: 0)
                put("final_b_to_a_sha256", artifact.finalBToASha256)
            }
            put("axle_valid", result.measurement.axle.valid)
            put("num_axles", result.measurement.axle.numAxles)
            putJsonArray("wheelbases_mm") {
                result.measurement.axle.wheelbasesMm.forEach { add(it) }
            }
            put("total_wheelbase_mm", result.measurement.axle.totalWheelbaseMm)
            put("front_overhang_mm", result.measurement.axle.frontOverhangMm)
            put("rear_overhang_mm", result.measurement.axle.rearOverhangMm)
            put("has_cargo_box", result.measurement.cargoBox.hasBox)
            put("box_outer_length_mm", result.measurement.cargoBox.outerLengthMm)
            put("box_outer_width_mm", result.measurement.cargoBox.outerWidthMm)
            put("box_depth_mm", result.measurement.cargoBox.depthMm)
            put("box_inner_width_mm", result.measurement.cargoBox.innerWidthMm)
            putJsonObject("overlay") {
                val overlay = requireNotNull(result.measurement.overlay)
                put("valid", overlay.valid)
                putJsonArray("vehicle_box") {
                    overlay.vehicleBox.forEach { point -> add(point.toJson()) }
                }
                put("has_cargo_box", overlay.hasCargoBox)
                putJsonArray("cargo_box") {
                    overlay.cargoBox.forEach { point -> add(point.toJson()) }
                }
                putJsonArray("axle_lines") {
                    overlay.axleLines.forEach { line ->
                        add(buildJsonArray {
                            add(line.from.toJson())
                            add(line.to.toJson())
                        })
                    }
                }
            }
            put("ground_nx", result.ground.nx)
            put("ground_ny", result.ground.ny)
            put("ground_nz", result.ground.nz)
            put("ground_d", result.ground.d)
            put("ground_valid", result.ground.valid)
        }
    }

    private fun MeasurementPoint3.toJson() = buildJsonArray {
        add(x)
        add(y)
        add(z)
    }
}
