package io.gomob.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class LaserScanStatusResponseTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun completeStatusJsonKeepsAllAuthoritativeMeasurementFields() {
        val response = json.decodeFromString<LaserScanStatusResponse>(
            """
            {
              "scan_id":209,
              "session_key":"laser-session-209",
              "status":"done",
              "align_method":"site",
              "site_revision":"site-sha-209",
              "region_revision":"region-sha-209",
              "points":548996,
              "pts_a":1049840,
              "pts_b":1000000,
              "result_object_key":"laser/209/fused.pcd",
              "unit_a_object_key":"laser/209/a.pcd",
              "unit_b_object_key":"laser/209/b.pcd",
              "measured_object_key":"laser/209/measured.pcd",
              "meas_mode":"bg_subtract",
              "measure_reason":"server_reason",
              "background_captured":false,
              "background_set":true,
              "background_compatible":true,
              "background_incompatible":false,
              "background_reason":"ready",
              "background_revision_id":17,
              "background_schema":"raw_units_v1",
              "fg_points":548996,
              "measured_points":530001,
              "length_mm":1768.0,
              "width_mm":531.0,
              "height_mm":763.0,
              "measure_valid":true,
              "compliant":false,
              "violations":["车宽超限"],
              "num_axles":3,
              "wheelbases_mm":[1450.0,1460.0],
              "total_wheelbase_mm":2910.0,
              "front_overhang_mm":930.0,
              "rear_overhang_mm":1040.0,
              "axle_valid":true,
              "has_cargo_box":true,
              "box_outer_length_mm":3100.0,
              "box_outer_width_mm":2050.0,
              "box_depth_mm":620.0,
              "box_inner_width_mm":1980.0,
              "overlay":{
                "valid":true,
                "vehicle_box":[[1.0,2.0,3.0]],
                "has_cargo_box":true,
                "cargo_box":[[4.0,5.0,6.0]],
                "axle_lines":[[[7.0,8.0,9.0],[10.0,11.0,12.0]]]
              },
              "ground_nx":0.01,
              "ground_ny":-0.02,
              "ground_nz":0.99,
              "ground_d":-123.0,
              "ground_valid":true
            }
            """.trimIndent(),
        )

        assertThat(response.scanId).isEqualTo(209)
        assertThat(response.sessionKey).isEqualTo("laser-session-209")
        assertThat(response.status).isEqualTo("done")
        assertThat(response.alignMethod).isEqualTo("site")
        assertThat(response.siteRevision).isEqualTo("site-sha-209")
        assertThat(response.regionRevision).isEqualTo("region-sha-209")
        assertThat(response.points).isEqualTo(548996)
        assertThat(response.ptsA).isEqualTo(1049840)
        assertThat(response.ptsB).isEqualTo(1000000)
        assertThat(response.resultObjectKey).isEqualTo("laser/209/fused.pcd")
        assertThat(response.measuredObjectKey).isEqualTo("laser/209/measured.pcd")
        assertThat(response.measMode).isEqualTo("bg_subtract")
        assertThat(response.measureReason).isEqualTo("server_reason")
        assertThat(response.backgroundSet).isTrue()
        assertThat(response.backgroundCompatible).isTrue()
        assertThat(response.backgroundIncompatible).isFalse()
        assertThat(response.backgroundReason).isEqualTo("ready")
        assertThat(response.backgroundRevisionId).isEqualTo(17L)
        assertThat(response.backgroundSchema).isEqualTo("raw_units_v1")
        assertThat(response.foregroundPoints).isEqualTo(548996)
        assertThat(response.measuredPoints).isEqualTo(530001)
        assertThat(response.lengthMm).isEqualTo(1768f)
        assertThat(response.widthMm).isEqualTo(531f)
        assertThat(response.heightMm).isEqualTo(763f)
        assertThat(response.measureValid).isTrue()
        assertThat(response.compliant).isFalse()
        assertThat(response.violations).containsExactly("车宽超限")
        assertThat(response.numAxles).isEqualTo(3)
        assertThat(response.wheelbasesMm).containsExactly(1450f, 1460f).inOrder()
        assertThat(response.totalWheelbaseMm).isEqualTo(2910f)
        assertThat(response.frontOverhangMm).isEqualTo(930f)
        assertThat(response.rearOverhangMm).isEqualTo(1040f)
        assertThat(response.axleValid).isTrue()
        assertThat(response.hasCargoBox).isTrue()
        assertThat(response.boxOuterLengthMm).isEqualTo(3100f)
        assertThat(response.boxOuterWidthMm).isEqualTo(2050f)
        assertThat(response.boxDepthMm).isEqualTo(620f)
        assertThat(response.boxInnerWidthMm).isEqualTo(1980f)
        assertThat(response.overlay?.valid).isTrue()
        assertThat(response.overlay?.vehicleBox).containsExactly(listOf(1f, 2f, 3f))
        assertThat(response.overlay?.cargoBox).containsExactly(listOf(4f, 5f, 6f))
        assertThat(response.overlay?.axleLines).containsExactly(
            listOf(listOf(7f, 8f, 9f), listOf(10f, 11f, 12f)),
        )
        assertThat(response.groundNx).isEqualTo(0.01f)
        assertThat(response.groundNy).isEqualTo(-0.02f)
        assertThat(response.groundNz).isEqualTo(0.99f)
        assertThat(response.groundD).isEqualTo(-123f)
        assertThat(response.groundValid).isTrue()
    }

    @Test
    fun legacyMeasurementReasonStillDecodes() {
        val response = json.decodeFromString<LaserScanStatusResponse>(
            """{"scan_id":1,"status":"done","meas_reason":"no_isolation"}""",
        )

        assertThat(response.measureReason).isNull()
        assertThat(response.legacyMeasureReason).isEqualTo("no_isolation")
    }

    @Test
    fun emptyRecoveryResponsesDecodeWithoutFakeScanIdentity() {
        val active = json.decodeFromString<LaserScanStatusResponse>("""{"active":false}""")
        val latest = json.decodeFromString<LaserScanStatusResponse>("""{"found":false}""")

        assertThat(active.active).isFalse()
        assertThat(active.scanId).isEqualTo(0)
        assertThat(active.status).isEmpty()
        assertThat(latest.found).isFalse()
        assertThat(latest.scanId).isEqualTo(0)
    }

    @Test
    fun activeResponseKeepsServerAppliedRegionProvenance() {
        val active = json.decodeFromString<LaserScanStatusResponse>(
            """
            {
              "active":true,
              "scan_id":209,
              "session_key":"session-209",
              "status":"capturing",
              "live_state":"scanning",
              "unit_a_ip":"192.168.9.101",
              "unit_b_ip":"192.168.9.102",
              "live_points_a":1200,
              "live_points_b":1100,
              "fusion_available":true,
              "region_filter":{
                "enabled":true,
                "points":[[0.0,0.0,0.0],[1000.0,0.0,0.0],[0.0,1000.0,0.0]],
                "b_to_a":[1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,0.0,1.0]
              }
            }
            """.trimIndent(),
        )

        assertThat(active.active).isTrue()
        assertThat(active.liveState).isEqualTo("scanning")
        assertThat(active.unitAIp).isEqualTo("192.168.9.101")
        assertThat(active.unitBIp).isEqualTo("192.168.9.102")
        assertThat(active.livePointsA).isEqualTo(1200)
        assertThat(active.livePointsB).isEqualTo(1100)
        assertThat(active.fusionAvailable).isTrue()
        assertThat(active.regionFilter?.enabled).isTrue()
        assertThat(active.regionFilter?.points).hasSize(3)
        assertThat(active.regionFilter?.bToA).hasSize(16)
    }
}
