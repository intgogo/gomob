package io.gomob.realtime

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class RealtimeEnvelopeParserTest {
    private val parser = RealtimeEnvelopeParser(
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    @Test
    fun laserPointEnvelopeUsesDedicatedLossyRoute() {
        assertThat(isLaserPointEnvelopeText("""{"type":"laser.points","payload":{}}""")).isTrue()
        assertThat(isLaserPointEnvelopeText("""{ "type" : "laser.points", "payload":{} }""")).isTrue()
        assertThat(isLaserPointEnvelopeText("""{"type":"laser.status","message":"laser.points"}""")).isFalse()
    }

    @Test
    fun laserPointCarriesServerCumulativeRegionCount() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"laser.points",
                  "payload":{
                    "session_key":"scan-1",
                    "unit":1,
                    "points":[1.0,2.0,3.0],
                    "h_angle_deg":12.5,
                    "source_points":955989
                  }
                }
                """.trimIndent(),
            ),
        ) as RealtimeEvent.LaserPoints

        assertThat(event.sessionKey).isEqualTo("scan-1")
        assertThat(event.unit).isEqualTo(1)
        assertThat(event.points.asList()).containsExactly(1f, 2f, 3f).inOrder()
        assertThat(event.hAngleDeg).isEqualTo(12.5f)
        assertThat(event.sourcePointCount).isEqualTo(955989)
    }

    @Test
    fun laserStatusCarriesReliableFinalSourceCounts() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"laser.status",
                  "payload":{
                    "session_key":"scan-1",
                    "state":"fusing",
                    "frames_a":120,
                    "frames_b":118,
                    "source_points_a":1094822,
                    "source_points_b":952898
                  }
                }
                """.trimIndent(),
            ),
        ) as RealtimeEvent.LaserStatus

        assertThat(event.sourcePointsA).isEqualTo(1_094_822)
        assertThat(event.sourcePointsB).isEqualTo(952_898)
    }

    @Test
    fun legacyLaserStatusWithoutSourceCountsRemainsCompatible() {
        val event = parser.toEvent(
            parser.parse(
                """{"type":"laser.status","payload":{"session_key":"scan-1","state":"scanning"}}""",
            ),
        ) as RealtimeEvent.LaserStatus

        assertThat(event.sourcePointsA).isNull()
        assertThat(event.sourcePointsB).isNull()
    }

    @Test
    fun unknownTypeBecomesUnknownEvent() {
        val event = parser.toEvent(
            parser.parse("""{"type":"new.future.event","payload":{"x":1},"frame_seq":9}"""),
        )

        assertThat(event).isInstanceOf(RealtimeEvent.Unknown::class.java)
        assertThat((event as RealtimeEvent.Unknown).envelope.type).isEqualTo("new.future.event")
    }

    @Test
    fun deliveredPayloadParses() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"msg.delivered",
                  "payload":{
                    "client_msg_id":"c-1",
                    "conversation_id":7,
                    "server_seq":42,
                    "message_id":99,
                    "created_at":"2026-05-08T12:00:00Z"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertThat(event).isEqualTo(
            RealtimeEvent.MessageDelivered(
                clientMsgId = "c-1",
                conversationId = 7,
                serverSeq = 42,
                messageId = 99,
                createdAt = "2026-05-08T12:00:00Z",
            ),
        )
    }

    @Test
    fun recvPayloadParsesMessageIdentity() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"msg.recv",
                  "payload":{
                    "message_id":100,
                    "conversation_id":7,
                    "server_seq":43,
                    "sender_id":31,
                    "kind":"text",
                    "content":{"text":"实时消息"},
                    "client_msg_id":"peer-c-1",
                    "created_at":"2026-05-08T12:00:01Z"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertThat(event).isEqualTo(
            RealtimeEvent.MessageReceived(
                messageId = 100,
                conversationId = 7,
                serverSeq = 43,
                senderId = 31,
                kind = "text",
                content = parser.parse("""{"type":"x","payload":{"text":"实时消息"}}""").payload,
                clientMsgId = "peer-c-1",
                createdAt = "2026-05-08T12:00:01Z",
            ),
        )
    }

    @Test
    fun laserScanDoneParsesCompleteAuthoritativeMeasurement() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"scan.fusion_done",
                  "payload":{
                    "kind":"laser",
                    "job_id":42,
                    "session_key":"sess-1",
                    "result_object_key":"k/fused.pcd",
                    "unit_a_object_key":"k/a.pcd",
                    "unit_b_object_key":"k/b.pcd",
                    "measured_object_key":"k/measured.pcd",
                    "points":548996,
                    "pts_a":1049840,
                    "pts_b":1000000,
                    "align_method":"site",
                    "site_revision":"site-sha-209",
                    "region_revision":"region-sha-209",
                    "site_quality_verified":true,
                    "site_quality_override":false,
                    "production_eligible":true,
                    "length_mm":1768.0,
                    "width_mm":531.0,
                    "height_mm":763.0,
                    "measure_valid":true,
                    "compliance_determined":true,
                    "compliance_reason":"rule-gb7258-v1",
                    "compliant":true,
                    "violations":[],
                    "meas_mode":"bg_subtract",
                    "measure_reason":"server_reason",
                    "background_captured":false,
                    "background_set":true,
                    "background_compatible":true,
                    "background_reason":"ready",
                    "background_revision_id":17,
                    "background_schema":"raw_units_v1",
                    "fg_points":548996,
                    "measured_points":530001,
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
                      "vehicle_box":[[1.0,2.0,3.0],[4.0,5.0,6.0]],
                      "has_cargo_box":true,
                      "cargo_box":[[7.0,8.0,9.0]],
                      "axle_lines":[[[10.0,11.0,12.0],[13.0,14.0,15.0]]]
                    },
                    "ground_nx":0.01,
                    "ground_ny":-0.02,
                    "ground_nz":0.99,
                    "ground_d":-123.0,
                    "ground_valid":true
                  }
                }
                """.trimIndent(),
            ),
        )

        assertThat(event).isInstanceOf(RealtimeEvent.LaserScanDone::class.java)
        val d = event as RealtimeEvent.LaserScanDone
        assertThat(d.jobId).isEqualTo(42)
        assertThat(d.sessionKey).isEqualTo("sess-1")
        assertThat(d.fusedObjectKey).isEqualTo("k/fused.pcd")
        assertThat(d.unitAObjectKey).isEqualTo("k/a.pcd")
        assertThat(d.unitBObjectKey).isEqualTo("k/b.pcd")
        assertThat(d.measuredObjectKey).isEqualTo("k/measured.pcd")
        assertThat(d.points).isEqualTo(548996)
        assertThat(d.ptsA).isEqualTo(1049840)
        assertThat(d.ptsB).isEqualTo(1000000)
        assertThat(d.alignMethod).isEqualTo("site")
        assertThat(d.siteRevision).isEqualTo("site-sha-209")
        assertThat(d.regionRevision).isEqualTo("region-sha-209")
        assertThat(d.siteQualityVerified).isTrue()
        assertThat(d.siteQualityOverride).isFalse()
        assertThat(d.productionEligible).isTrue()
        assertThat(d.lengthMm).isEqualTo(1768.0f)
        assertThat(d.widthMm).isEqualTo(531.0f)
        assertThat(d.heightMm).isEqualTo(763.0f)
        assertThat(d.measureValid).isTrue()
        assertThat(d.complianceDetermined).isTrue()
        assertThat(d.complianceReason).isEqualTo("rule-gb7258-v1")
        assertThat(d.compliant).isTrue()
        assertThat(d.violations).isEmpty()
        assertThat(d.measMode).isEqualTo("bg_subtract")
        assertThat(d.measureReason).isEqualTo("server_reason")
        assertThat(d.backgroundCaptured).isFalse()
        assertThat(d.backgroundSet).isTrue()
        assertThat(d.backgroundCompatible).isTrue()
        assertThat(d.backgroundReason).isEqualTo("ready")
        assertThat(d.backgroundRevisionId).isEqualTo(17L)
        assertThat(d.backgroundSchema).isEqualTo("raw_units_v1")
        assertThat(d.foregroundPoints).isEqualTo(548996)
        assertThat(d.measuredPoints).isEqualTo(530001)
        assertThat(d.numAxles).isEqualTo(3)
        assertThat(d.wheelbasesMm).containsExactly(1450f, 1460f).inOrder()
        assertThat(d.totalWheelbaseMm).isEqualTo(2910f)
        assertThat(d.frontOverhangMm).isEqualTo(930f)
        assertThat(d.rearOverhangMm).isEqualTo(1040f)
        assertThat(d.axleValid).isTrue()
        assertThat(d.hasCargoBox).isTrue()
        assertThat(d.boxOuterLengthMm).isEqualTo(3100f)
        assertThat(d.boxOuterWidthMm).isEqualTo(2050f)
        assertThat(d.boxDepthMm).isEqualTo(620f)
        assertThat(d.boxInnerWidthMm).isEqualTo(1980f)
        assertThat(d.overlay?.valid).isTrue()
        assertThat(d.overlay?.vehicleBox).containsExactly(listOf(1f, 2f, 3f), listOf(4f, 5f, 6f)).inOrder()
        assertThat(d.overlay?.cargoBox).containsExactly(listOf(7f, 8f, 9f))
        assertThat(d.overlay?.axleLines).containsExactly(
            listOf(listOf(10f, 11f, 12f), listOf(13f, 14f, 15f)),
        )
        assertThat(d.groundNx).isEqualTo(0.01f)
        assertThat(d.groundNy).isEqualTo(-0.02f)
        assertThat(d.groundNz).isEqualTo(0.99f)
        assertThat(d.groundD).isEqualTo(-123f)
        assertThat(d.groundValid).isTrue()
    }

    @Test
    fun laserScanDoneCarriesViolations() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"scan.fusion_done",
                  "payload":{
                    "kind":"laser","session_key":"s","result_object_key":"f",
                    "length_mm":13000.0,"width_mm":2600.0,"height_mm":4100.0,
                    "measure_valid":true,"compliance_determined":true,"compliant":false,
                    "violations":["车长超限","车宽超限","车高超限"]
                  }
                }
                """.trimIndent(),
            ),
        )
        val d = event as RealtimeEvent.LaserScanDone
        assertThat(d.complianceDetermined).isTrue()
        assertThat(d.compliant).isFalse()
        assertThat(d.violations).containsExactly("车长超限", "车宽超限", "车高超限")
    }

    @Test
    fun transcriptUpdatedPayloadParses() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"msg.transcript.updated",
                  "payload":{
                    "message_id":100,
                    "conversation_id":7,
                    "server_seq":43,
                    "kind":"voice",
                    "content":{
                      "transcript_status":"done",
                      "transcript_normalized_text":"请复核第三工位"
                    },
                    "updated_at":"2026-05-08T12:00:03Z"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertThat(event).isEqualTo(
            RealtimeEvent.TranscriptUpdated(
                messageId = 100,
                conversationId = 7,
                serverSeq = 43,
                kind = "voice",
                content = parser.parse(
                    """{"type":"x","payload":{"transcript_status":"done","transcript_normalized_text":"请复核第三工位"}}""",
                ).payload,
                updatedAt = "2026-05-08T12:00:03Z",
            ),
        )
    }
}
