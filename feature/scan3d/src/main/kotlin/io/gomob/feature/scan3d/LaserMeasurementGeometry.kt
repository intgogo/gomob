package io.gomob.feature.scan3d

import io.gomob.data.scan.MeasurementPoint3
import io.gomob.data.scan.VehicleMeasurement
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Filament 当前相机的列主序 view-projection 矩阵及对应视口。 */
data class CameraProjectionSnapshot(
    val viewProjection: DoubleArray,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val revision: Long,
)

internal data class MeasurementScreenPoint(
    val x: Float,
    val y: Float,
    val depth: Float,
)

internal enum class MeasurementLineStyle {
    VEHICLE_BOX,
    CARGO_BOX,
    AXLE,
    DIMENSION,
    DIMENSION_EXTENSION,
    LWH_DIMENSION,
    LWH_EXTENSION,
    CARGO_DIMENSION,
    CARGO_EXTENSION,
}

internal enum class MeasurementLabelStyle { DIMENSION, LWH, CARGO }

internal data class MeasurementWorldLine(
    val from: MeasurementPoint3,
    val to: MeasurementPoint3,
    val style: MeasurementLineStyle,
    val arrowHeads: Boolean = false,
)

internal data class MeasurementWorldLabel(
    val anchor: MeasurementPoint3,
    val name: String,
    val value: String,
    val style: MeasurementLabelStyle,
    val verticalOffsetPx: Float = 0f,
)

internal data class VehicleMeasurementScene(
    val lines: List<MeasurementWorldLine>,
    val labels: List<MeasurementWorldLabel>,
)

private val BOX_EDGES = arrayOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 0,
    4 to 5, 5 to 6, 6 to 7, 7 to 4,
    0 to 4, 1 to 5, 2 to 6, 3 to 7,
)

/**
 * 按网页 `renderOverlayDimensions` 的同一规则生成世界系标注。
 * 几何只来自服务端 overlay，数值只来自同次 measured 结果，端侧不拟合外廓。
 */
internal fun buildVehicleMeasurementScene(measurement: VehicleMeasurement): VehicleMeasurementScene? {
    val overlay = measurement.overlay ?: return null
    val vehicleBox = overlay.vehicleBox
    if (!measurement.valid || !overlay.valid || !vehicleBox.isValidBox()) return null

    val lines = ArrayList<MeasurementWorldLine>(64)
    val labels = ArrayList<MeasurementWorldLabel>(12)

    addBox(lines, vehicleBox, MeasurementLineStyle.VEHICLE_BOX)

    val cargoBox = overlay.cargoBox.takeIf {
        overlay.hasCargoBox && measurement.cargoBox.hasBox && it.isValidBox()
    }
    if (cargoBox != null) addBox(lines, cargoBox, MeasurementLineStyle.CARGO_BOX)

    val axleLines = overlay.axleLines.filter { it.from.isFinitePoint() && it.to.isFinitePoint() }
    axleLines.forEach {
        lines += MeasurementWorldLine(it.from, it.to, MeasurementLineStyle.AXLE)
    }

    val heightOffset = (vehicleBox[4] - vehicleBox[0]) * 0.14f
    val lengthOffset = (vehicleBox[1] - vehicleBox[0]) * 0.08f
    val widthOffset = (vehicleBox[2] - vehicleBox[1]) * 0.20f
    addDimensionIfValid(
        lines, labels, vehicleBox[4], vehicleBox[5], heightOffset,
        "车长", measurement.lengthMm,
        MeasurementLineStyle.LWH_DIMENSION, MeasurementLineStyle.LWH_EXTENSION,
        MeasurementLabelStyle.LWH,
    )
    addDimensionIfValid(
        lines, labels, vehicleBox[1], vehicleBox[2], lengthOffset,
        "车宽", measurement.widthMm,
        MeasurementLineStyle.LWH_DIMENSION, MeasurementLineStyle.LWH_EXTENSION,
        MeasurementLabelStyle.LWH,
    )
    addDimensionIfValid(
        lines, labels, vehicleBox[2], vehicleBox[6], widthOffset,
        "车高", measurement.heightMm,
        MeasurementLineStyle.LWH_DIMENSION, MeasurementLineStyle.LWH_EXTENSION,
        MeasurementLabelStyle.LWH,
    )

    if (cargoBox != null) {
        val cargoWidthOffset = (cargoBox[3] - cargoBox[0]) * 0.25f
        val cargoLengthOffset = (cargoBox[0] - cargoBox[1]) * 0.12f
        addDimensionIfValid(
            lines, labels, cargoBox[7], cargoBox[6], cargoWidthOffset,
            "箱长", measurement.cargoBox.outerLengthMm,
            MeasurementLineStyle.CARGO_DIMENSION, MeasurementLineStyle.CARGO_EXTENSION,
            MeasurementLabelStyle.CARGO,
        )
        addDimensionIfValid(
            lines, labels, cargoBox[4], cargoBox[7], cargoLengthOffset,
            "箱宽", measurement.cargoBox.outerWidthMm,
            MeasurementLineStyle.CARGO_DIMENSION, MeasurementLineStyle.CARGO_EXTENSION,
            MeasurementLabelStyle.CARGO,
        )
        addDimensionIfValid(
            lines, labels, cargoBox[3], cargoBox[7], cargoWidthOffset,
            "箱深", measurement.cargoBox.depthMm,
            MeasurementLineStyle.CARGO_DIMENSION, MeasurementLineStyle.CARGO_EXTENSION,
            MeasurementLabelStyle.CARGO,
        )
    }

    addAxleDimensionChain(lines, labels, vehicleBox, axleLines, measurement)
    return VehicleMeasurementScene(lines, labels)
}

private fun addBox(
    target: MutableList<MeasurementWorldLine>,
    corners: List<MeasurementPoint3>,
    style: MeasurementLineStyle,
) {
    BOX_EDGES.forEach { (a, b) -> target += MeasurementWorldLine(corners[a], corners[b], style) }
}

private fun addDimensionIfValid(
    lines: MutableList<MeasurementWorldLine>,
    labels: MutableList<MeasurementWorldLabel>,
    geometryFrom: MeasurementPoint3,
    geometryTo: MeasurementPoint3,
    offset: MeasurementPoint3,
    name: String,
    valueMm: Float,
    dimensionStyle: MeasurementLineStyle,
    extensionStyle: MeasurementLineStyle,
    labelStyle: MeasurementLabelStyle,
    verticalOffsetPx: Float = 0f,
) {
    if (!valueMm.isFinite() || valueMm <= 0f || !offset.isFinitePoint()) return
    val from = geometryFrom + offset
    val to = geometryTo + offset
    lines += MeasurementWorldLine(
        geometryFrom + offset * 0.25f,
        geometryFrom + offset * 1.15f,
        extensionStyle,
    )
    lines += MeasurementWorldLine(
        geometryTo + offset * 0.25f,
        geometryTo + offset * 1.15f,
        extensionStyle,
    )
    lines += MeasurementWorldLine(from, to, dimensionStyle, arrowHeads = true)
    labels += MeasurementWorldLabel(
        anchor = midpoint(from, to),
        name = name,
        value = String.format(Locale.US, "%,d", valueMm.roundToInt()),
        style = labelStyle,
        verticalOffsetPx = verticalOffsetPx,
    )
}

private fun addAxleDimensionChain(
    lines: MutableList<MeasurementWorldLine>,
    labels: MutableList<MeasurementWorldLabel>,
    vehicleBox: List<MeasurementPoint3>,
    axleLines: List<io.gomob.data.scan.MeasurementLine3>,
    measurement: VehicleMeasurement,
) {
    val axle = measurement.axle
    if (!axle.valid || axleLines.size < 2) return
    val values = buildList {
        add("前悬" to axle.frontOverhangMm)
        axle.wheelbasesMm.forEach { add("" to it) }
        add("后悬" to axle.rearOverhangMm)
    }
    val nodes = buildList {
        add(vehicleBox[0])
        axleLines.forEach { add(it.from) }
        add(vehicleBox[1])
    }
    if (values.size != nodes.size - 1 || values.any { !it.second.isFinite() || it.second <= 0f }) return

    val orderedValues = if (
        abs(distance(nodes[0], nodes[1]) - axle.frontOverhangMm) >
        abs(distance(nodes[0], nodes[1]) - axle.rearOverhangMm)
    ) {
        values.asReversed()
    } else {
        values
    }
    val offset = (vehicleBox[0] - vehicleBox[3]) * 0.18f
    if (!offset.isFinitePoint()) return
    nodes.forEach { node ->
        lines += MeasurementWorldLine(
            node + offset * 0.25f,
            node + offset * 1.15f,
            MeasurementLineStyle.DIMENSION_EXTENSION,
        )
    }
    for (index in 0 until nodes.lastIndex) {
        val from = nodes[index] + offset
        val to = nodes[index + 1] + offset
        lines += MeasurementWorldLine(
            from, to, MeasurementLineStyle.DIMENSION, arrowHeads = true,
        )
        labels += MeasurementWorldLabel(
            anchor = midpoint(from, to),
            name = orderedValues[index].first,
            value = String.format(Locale.US, "%,d", orderedValues[index].second.roundToInt()),
            style = MeasurementLabelStyle.DIMENSION,
            verticalOffsetPx = if (index % 2 == 0) -18f else 18f,
        )
    }
}

/** 与网页同样只按 w/depth 裁剪，不裁 x/y，允许框线延伸到视口外。 */
internal fun projectMeasurementPoint(
    snapshot: CameraProjectionSnapshot,
    point: MeasurementPoint3,
): MeasurementScreenPoint? {
    if (snapshot.viewProjection.size < 16 || snapshot.viewportWidthPx <= 0 || snapshot.viewportHeightPx <= 0 ||
        !point.isFinitePoint()
    ) return null
    val matrix = snapshot.viewProjection
    val x = point.x.toDouble()
    val y = point.y.toDouble()
    val z = point.z.toDouble()
    val clipX = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
    val clipY = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
    val clipZ = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
    val clipW = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15]
    if (!clipX.isFinite() || !clipY.isFinite() || !clipZ.isFinite() || !clipW.isFinite() || clipW <= 0.0) return null
    val ndcZ = clipZ / clipW
    if (!ndcZ.isFinite() || ndcZ < -1.0 || ndcZ > 1.0) return null
    val ndcX = clipX / clipW
    val ndcY = clipY / clipW
    return MeasurementScreenPoint(
        x = ((ndcX + 1.0) * 0.5 * snapshot.viewportWidthPx).toFloat(),
        y = ((1.0 - ndcY) * 0.5 * snapshot.viewportHeightPx).toFloat(),
        depth = ndcZ.toFloat(),
    )
}

internal fun multiplyColumnMajor4x4(left: DoubleArray, right: DoubleArray): DoubleArray {
    require(left.size >= 16 && right.size >= 16)
    val result = DoubleArray(16)
    for (column in 0 until 4) {
        for (row in 0 until 4) {
            var value = 0.0
            for (k in 0 until 4) {
                value += left[k * 4 + row] * right[column * 4 + k]
            }
            result[column * 4 + row] = value
        }
    }
    return result
}

private fun List<MeasurementPoint3>.isValidBox(): Boolean = size == 8 && all { it.isFinitePoint() }

private fun MeasurementPoint3.isFinitePoint(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

private operator fun MeasurementPoint3.plus(other: MeasurementPoint3) =
    MeasurementPoint3(x + other.x, y + other.y, z + other.z)

private operator fun MeasurementPoint3.minus(other: MeasurementPoint3) =
    MeasurementPoint3(x - other.x, y - other.y, z - other.z)

private operator fun MeasurementPoint3.times(scale: Float) =
    MeasurementPoint3(x * scale, y * scale, z * scale)

private fun midpoint(a: MeasurementPoint3, b: MeasurementPoint3) =
    MeasurementPoint3((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f, (a.z + b.z) * 0.5f)

private fun distance(a: MeasurementPoint3, b: MeasurementPoint3): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
}
