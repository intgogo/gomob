package io.gomob.data.scan

/**
 * 车型分类目录（逆向自原厂 JCHY，docs/architecture/16 §4.1；与 native/measurement/vehicle_catalog 同源）。
 * 26 项，编号即语义分组：货车 0–15、挂车 50–59（中间 16–49、60+ 未占用）。
 * App 车型下拉的单一展示真理源；跨层只下发 [VehicleType.id]（Int），服务端据此套 carType 偏移/合规。
 */
enum class VehicleGroup { TRUCK, TRAILER }

data class VehicleType(
    val id: Int,
    val name: String,
    val group: VehicleGroup,
    val tank: Boolean = false,  // 罐体型（洒水/搅拌/罐挂）
    val crane: Boolean = false, // 吊车型
)

object VehicleTypeCatalog {
    /** 全 26 车型（顺序即下拉展示序：货车在前、挂车在后）。 */
    val all: List<VehicleType> = listOf(
        // 货车 #货车类型# 0–15
        VehicleType(0, "牵引头", VehicleGroup.TRUCK),
        VehicleType(1, "吊车", VehicleGroup.TRUCK, crane = true),
        VehicleType(2, "常规", VehicleGroup.TRUCK),
        VehicleType(3, "路边清障车", VehicleGroup.TRUCK),
        VehicleType(4, "垃圾清理车", VehicleGroup.TRUCK),
        VehicleType(5, "洒水罐车", VehicleGroup.TRUCK, tank = true),
        VehicleType(6, "小型平板货车", VehicleGroup.TRUCK),
        VehicleType(7, "水泥搅拌车", VehicleGroup.TRUCK, tank = true),
        VehicleType(8, "大型平板货车", VehicleGroup.TRUCK),
        VehicleType(9, "特殊吊车", VehicleGroup.TRUCK, crane = true),
        VehicleType(10, "特殊栏板吊车", VehicleGroup.TRUCK, crane = true),
        VehicleType(11, "专项特殊车", VehicleGroup.TRUCK),
        VehicleType(12, "箱式尾板车", VehicleGroup.TRUCK),
        VehicleType(13, "自卸式货车", VehicleGroup.TRUCK),
        VehicleType(14, "仓栅式货车", VehicleGroup.TRUCK),
        VehicleType(15, "箱式货车", VehicleGroup.TRUCK),
        // 挂车 #挂车类型# 50–59
        VehicleType(50, "常规挂车", VehicleGroup.TRAILER),
        VehicleType(51, "光板挂车", VehicleGroup.TRAILER),
        VehicleType(52, "光板挂车（带杆）", VehicleGroup.TRAILER),
        VehicleType(53, "常规罐体挂车", VehicleGroup.TRAILER, tank = true),
        VehicleType(54, "低平板挂车", VehicleGroup.TRAILER),
        VehicleType(55, "异型挂车", VehicleGroup.TRAILER),
        VehicleType(56, "箱式挂车", VehicleGroup.TRAILER),
        VehicleType(57, "仓栅式挂车", VehicleGroup.TRAILER),
        VehicleType(58, "下灰式罐体挂车", VehicleGroup.TRAILER, tank = true),
        VehicleType(59, "水泥罐体挂车", VehicleGroup.TRAILER, tank = true),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: Int?): VehicleType? = id?.let { byId[it] }

    /** 默认选中：常规货车(2)，最常见。 */
    val default: VehicleType = byId.getValue(2)
}
