package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import io.gomob.data.scan.MeasurementLine3
import io.gomob.data.scan.MeasurementPoint3
import io.gomob.data.scan.VehicleAxleMeasurement
import io.gomob.data.scan.VehicleCargoBoxMeasurement
import io.gomob.data.scan.VehicleMeasurement
import io.gomob.data.scan.VehicleMeasurementOverlay
import org.junit.Test

class LaserMeasurementGeometryTest {

    @Test
    fun vehicleBoxUsesEightCornersTwelveEdgesAndThreeLwhDimensions() {
        val scene = requireNotNull(
            buildVehicleMeasurementScene(
                measurement(
                    lengthMm = 100f,
                    widthMm = 40f,
                    heightMm = 30f,
                    vehicleBox = box(length = 100f, width = 40f, height = 30f),
                ),
            ),
        )

        val boxLines = scene.lines.filter { it.style == MeasurementLineStyle.VEHICLE_BOX }
        assertThat(boxLines).hasSize(12)
        assertThat(boxLines.map { it.from to it.to }).containsExactlyElementsIn(
            listOf(
                p(0f, 0f, 0f) to p(100f, 0f, 0f),
                p(100f, 0f, 0f) to p(100f, 40f, 0f),
                p(100f, 40f, 0f) to p(0f, 40f, 0f),
                p(0f, 40f, 0f) to p(0f, 0f, 0f),
                p(0f, 0f, 30f) to p(100f, 0f, 30f),
                p(100f, 0f, 30f) to p(100f, 40f, 30f),
                p(100f, 40f, 30f) to p(0f, 40f, 30f),
                p(0f, 40f, 30f) to p(0f, 0f, 30f),
                p(0f, 0f, 0f) to p(0f, 0f, 30f),
                p(100f, 0f, 0f) to p(100f, 0f, 30f),
                p(100f, 40f, 0f) to p(100f, 40f, 30f),
                p(0f, 40f, 0f) to p(0f, 40f, 30f),
            ),
        ).inOrder()
        assertThat(boxLines.all { !it.arrowHeads }).isTrue()

        val dimensions = scene.lines.filter { it.style == MeasurementLineStyle.LWH_DIMENSION }
        assertThat(dimensions).hasSize(3)
        assertThat(dimensions.all { it.arrowHeads }).isTrue()
        assertThat(scene.lines.count { it.style == MeasurementLineStyle.LWH_EXTENSION }).isEqualTo(6)
        assertThat(scene.labels.filter { it.style == MeasurementLabelStyle.LWH }.map { it.name to it.value })
            .containsExactly("车长" to "100", "车宽" to "40", "车高" to "30")
            .inOrder()
    }

    @Test
    fun cargoBoxUsesTwelveEdgesAndLengthWidthDepthDimensions() {
        val cargo = box(
            lMin = 10f,
            wMin = 5f,
            hMin = 10f,
            length = 70f,
            width = 30f,
            height = 15f,
        )
        val scene = requireNotNull(
            buildVehicleMeasurementScene(
                measurement(
                    vehicleBox = box(length = 100f, width = 40f, height = 30f),
                    cargoBox = VehicleCargoBoxMeasurement(
                        hasBox = true,
                        outerLengthMm = 70f,
                        outerWidthMm = 30f,
                        depthMm = 15f,
                        innerWidthMm = 26f,
                    ),
                    overlayCargoBox = cargo,
                    overlayHasCargoBox = true,
                ),
            ),
        )

        assertThat(scene.lines.count { it.style == MeasurementLineStyle.CARGO_BOX }).isEqualTo(12)
        assertThat(scene.lines.count { it.style == MeasurementLineStyle.CARGO_EXTENSION }).isEqualTo(6)
        val dimensions = scene.lines.filter { it.style == MeasurementLineStyle.CARGO_DIMENSION }
        assertThat(dimensions).hasSize(3)
        assertThat(dimensions.all { it.arrowHeads }).isTrue()
        assertThat(scene.labels.filter { it.style == MeasurementLabelStyle.CARGO }.map { it.name to it.value })
            .containsExactly("箱长" to "70", "箱宽" to "30", "箱深" to "15")
            .inOrder()
    }

    @Test
    fun twoAxlesProduceFrontWheelbaseRearDimensionChain() {
        val axles = listOf(axleLine(20f), axleLine(70f))
        val scene = requireNotNull(
            buildVehicleMeasurementScene(
                measurement(
                    vehicleBox = box(length = 100f, width = 40f, height = 30f),
                    axle = VehicleAxleMeasurement(
                        valid = true,
                        numAxles = 2,
                        wheelbasesMm = listOf(50f),
                        totalWheelbaseMm = 50f,
                        frontOverhangMm = 20f,
                        rearOverhangMm = 30f,
                    ),
                    axleLines = axles,
                ),
            ),
        )

        assertThat(scene.lines.count { it.style == MeasurementLineStyle.AXLE }).isEqualTo(2)
        assertThat(scene.lines.count { it.style == MeasurementLineStyle.DIMENSION_EXTENSION }).isEqualTo(4)
        val dimensions = scene.lines.filter { it.style == MeasurementLineStyle.DIMENSION }
        assertThat(dimensions).hasSize(3)
        assertThat(dimensions.all { it.arrowHeads }).isTrue()
        val labels = scene.labels.filter { it.style == MeasurementLabelStyle.DIMENSION }
        assertThat(labels.map { it.name to it.value })
            .containsExactly("前悬" to "20", "" to "50", "后悬" to "30")
            .inOrder()
        assertThat(labels.map { it.verticalOffsetPx }).containsExactly(-18f, 18f, -18f).inOrder()
    }

    @Test
    fun fourAxlesReverseSemanticChainWhenLowEndMatchesRearOverhang() {
        val scene = requireNotNull(
            buildVehicleMeasurementScene(
                measurement(
                    vehicleBox = box(length = 140f, width = 40f, height = 30f),
                    axle = VehicleAxleMeasurement(
                        valid = true,
                        numAxles = 4,
                        wheelbasesMm = listOf(30f, 40f, 30f),
                        totalWheelbaseMm = 100f,
                        frontOverhangMm = 30f,
                        rearOverhangMm = 10f,
                    ),
                    axleLines = listOf(axleLine(10f), axleLine(40f), axleLine(80f), axleLine(110f)),
                ),
            ),
        )

        assertThat(scene.lines.count { it.style == MeasurementLineStyle.AXLE }).isEqualTo(4)
        assertThat(scene.lines.count { it.style == MeasurementLineStyle.DIMENSION }).isEqualTo(5)
        val labels = scene.labels.filter { it.style == MeasurementLabelStyle.DIMENSION }
        assertThat(labels.map { it.name to it.value })
            .containsExactly(
                "后悬" to "10",
                "" to "30",
                "" to "40",
                "" to "30",
                "前悬" to "30",
            )
            .inOrder()
        assertThat(labels.map { it.verticalOffsetPx })
            .containsExactly(-18f, 18f, -18f, 18f, -18f)
            .inOrder()
    }

    @Test
    fun mismatchedWheelbaseCountKeepsAxleGeometryButOmitsDimensionChain() {
        val scene = requireNotNull(
            buildVehicleMeasurementScene(
                measurement(
                    vehicleBox = box(length = 120f, width = 40f, height = 30f),
                    axle = VehicleAxleMeasurement(
                        valid = true,
                        numAxles = 3,
                        wheelbasesMm = listOf(40f),
                        totalWheelbaseMm = 80f,
                        frontOverhangMm = 20f,
                        rearOverhangMm = 20f,
                    ),
                    axleLines = listOf(axleLine(20f), axleLine(60f), axleLine(100f)),
                ),
            ),
        )

        assertThat(scene.lines.count { it.style == MeasurementLineStyle.AXLE }).isEqualTo(3)
        assertThat(scene.lines.none { it.style == MeasurementLineStyle.DIMENSION }).isTrue()
        assertThat(scene.lines.none { it.style == MeasurementLineStyle.DIMENSION_EXTENSION }).isTrue()
        assertThat(scene.labels.none { it.style == MeasurementLabelStyle.DIMENSION }).isTrue()
    }

    @Test
    fun invalidMeasurementOrVehicleBoxReturnsNull() {
        val validBox = box(length = 100f, width = 40f, height = 30f)

        assertThat(buildVehicleMeasurementScene(measurement(valid = false, vehicleBox = validBox))).isNull()
        assertThat(
            buildVehicleMeasurementScene(
                measurement(vehicleBox = validBox, overlayValid = false),
            ),
        ).isNull()
        assertThat(
            buildVehicleMeasurementScene(
                measurement(vehicleBox = validBox.dropLast(1)),
            ),
        ).isNull()
        assertThat(
            buildVehicleMeasurementScene(
                measurement(vehicleBox = validBox.toMutableList().also { it[0] = p(Float.NaN, 0f, 0f) }),
            ),
        ).isNull()
    }

    @Test
    fun invalidOptionalGeometryAndNonPositiveDimensionsAreOmitted() {
        val scene = requireNotNull(
            buildVehicleMeasurementScene(
                measurement(
                    lengthMm = 100f,
                    widthMm = 0f,
                    heightMm = Float.NaN,
                    vehicleBox = box(length = 100f, width = 40f, height = 30f),
                    cargoBox = VehicleCargoBoxMeasurement(
                        hasBox = true,
                        outerLengthMm = 70f,
                        outerWidthMm = 30f,
                        depthMm = 15f,
                    ),
                    overlayCargoBox = box(length = 70f, width = 30f, height = 15f).dropLast(1),
                    overlayHasCargoBox = true,
                    axle = VehicleAxleMeasurement(valid = true, numAxles = 2, wheelbasesMm = listOf(50f)),
                    axleLines = listOf(
                        axleLine(20f),
                        MeasurementLine3(p(Float.POSITIVE_INFINITY, 0f, 0f), p(70f, 40f, 0f)),
                    ),
                ),
            ),
        )

        assertThat(scene.lines.none { it.style == MeasurementLineStyle.CARGO_BOX }).isTrue()
        assertThat(scene.lines.none { it.style == MeasurementLineStyle.CARGO_DIMENSION }).isTrue()
        assertThat(scene.lines.count { it.style == MeasurementLineStyle.AXLE }).isEqualTo(1)
        assertThat(scene.lines.count { it.style == MeasurementLineStyle.LWH_DIMENSION }).isEqualTo(1)
        assertThat(scene.labels.filter { it.style == MeasurementLabelStyle.LWH }.map { it.name })
            .containsExactly("车长")
    }

    @Test
    fun identityProjectionMapsNdcToViewportAndKeepsDepth() {
        val snapshot = CameraProjectionSnapshot(
            viewProjection = identityMatrix(),
            viewportWidthPx = 200,
            viewportHeightPx = 100,
            revision = 7,
        )

        assertThat(projectMeasurementPoint(snapshot, p(0f, 0f, 0f)))
            .isEqualTo(MeasurementScreenPoint(100f, 50f, 0f))
        assertThat(projectMeasurementPoint(snapshot, p(1f, -1f, 1f)))
            .isEqualTo(MeasurementScreenPoint(200f, 100f, 1f))
        assertThat(projectMeasurementPoint(snapshot, p(-1f, 1f, -1f)))
            .isEqualTo(MeasurementScreenPoint(0f, 0f, -1f))
        // 与网页一致，不按 x/y 裁剪；框线允许延伸到视口外。
        assertThat(projectMeasurementPoint(snapshot, p(2f, -3f, 0f)))
            .isEqualTo(MeasurementScreenPoint(300f, 200f, 0f))
    }

    @Test
    fun projectionRejectsDepthOutsideClipRangeAndNonPositiveW() {
        val identity = CameraProjectionSnapshot(identityMatrix(), 200, 100, revision = 1)
        assertThat(projectMeasurementPoint(identity, p(0f, 0f, 1.001f))).isNull()
        assertThat(projectMeasurementPoint(identity, p(0f, 0f, -1.001f))).isNull()

        val zeroW = identityMatrix().also { it[15] = 0.0 }
        val negativeW = identityMatrix().also { it[15] = -1.0 }
        assertThat(projectMeasurementPoint(CameraProjectionSnapshot(zeroW, 200, 100, 2), p(0f, 0f, 0f))).isNull()
        assertThat(projectMeasurementPoint(CameraProjectionSnapshot(negativeW, 200, 100, 3), p(0f, 0f, 0f))).isNull()
    }

    @Test
    fun projectionRejectsInvalidMatrixViewportPointAndClipValues() {
        assertThat(projectMeasurementPoint(CameraProjectionSnapshot(DoubleArray(15), 200, 100, 1), p(0f, 0f, 0f)))
            .isNull()
        assertThat(projectMeasurementPoint(CameraProjectionSnapshot(identityMatrix(), 0, 100, 1), p(0f, 0f, 0f)))
            .isNull()
        assertThat(projectMeasurementPoint(CameraProjectionSnapshot(identityMatrix(), 200, 100, 1), p(Float.NaN, 0f, 0f)))
            .isNull()
        val nonFiniteClip = identityMatrix().also { it[0] = Double.POSITIVE_INFINITY }
        assertThat(projectMeasurementPoint(CameraProjectionSnapshot(nonFiniteClip, 200, 100, 1), p(1f, 0f, 0f)))
            .isNull()
    }

    @Test
    fun columnMajorMultiplicationKeepsLeftTranslationAfterRightScale() {
        val translation = doubleArrayOf(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            10.0, 20.0, 30.0, 1.0,
        )
        val scale = doubleArrayOf(
            2.0, 0.0, 0.0, 0.0,
            0.0, 3.0, 0.0, 0.0,
            0.0, 0.0, 4.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )

        assertThat(multiplyColumnMajor4x4(translation, scale).asList()).containsExactly(
            2.0, 0.0, 0.0, 0.0,
            0.0, 3.0, 0.0, 0.0,
            0.0, 0.0, 4.0, 0.0,
            10.0, 20.0, 30.0, 1.0,
        ).inOrder()
        assertThat(multiplyColumnMajor4x4(scale, translation).asList()).containsExactly(
            2.0, 0.0, 0.0, 0.0,
            0.0, 3.0, 0.0, 0.0,
            0.0, 0.0, 4.0, 0.0,
            20.0, 60.0, 120.0, 1.0,
        ).inOrder()
    }

    private fun measurement(
        valid: Boolean = true,
        lengthMm: Float = 100f,
        widthMm: Float = 40f,
        heightMm: Float = 30f,
        vehicleBox: List<MeasurementPoint3>,
        overlayValid: Boolean = true,
        cargoBox: VehicleCargoBoxMeasurement = VehicleCargoBoxMeasurement(),
        overlayHasCargoBox: Boolean = false,
        overlayCargoBox: List<MeasurementPoint3> = emptyList(),
        axle: VehicleAxleMeasurement = VehicleAxleMeasurement(),
        axleLines: List<MeasurementLine3> = emptyList(),
    ) = VehicleMeasurement(
        lengthMm = lengthMm,
        widthMm = widthMm,
        heightMm = heightMm,
        valid = valid,
        compliant = false,
        violations = emptyList(),
        axle = axle,
        cargoBox = cargoBox,
        overlay = VehicleMeasurementOverlay(
            valid = overlayValid,
            vehicleBox = vehicleBox,
            hasCargoBox = overlayHasCargoBox,
            cargoBox = overlayCargoBox,
            axleLines = axleLines,
        ),
    )

    private fun box(
        lMin: Float = 0f,
        wMin: Float = 0f,
        hMin: Float = 0f,
        length: Float,
        width: Float,
        height: Float,
    ): List<MeasurementPoint3> {
        val lMax = lMin + length
        val wMax = wMin + width
        val hMax = hMin + height
        return listOf(
            p(lMin, wMin, hMin),
            p(lMax, wMin, hMin),
            p(lMax, wMax, hMin),
            p(lMin, wMax, hMin),
            p(lMin, wMin, hMax),
            p(lMax, wMin, hMax),
            p(lMax, wMax, hMax),
            p(lMin, wMax, hMax),
        )
    }

    private fun axleLine(lengthPosition: Float) = MeasurementLine3(
        from = p(lengthPosition, 0f, 0f),
        to = p(lengthPosition, 40f, 0f),
    )

    private fun p(x: Float, y: Float, z: Float) = MeasurementPoint3(x, y, z)

    private fun identityMatrix() = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0,
    )
}
