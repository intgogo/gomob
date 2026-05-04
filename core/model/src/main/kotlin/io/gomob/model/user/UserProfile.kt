package io.gomob.model.user

/**
 * 当前登录用户在 App 内的领域模型 — 与服务端 UserDto 解耦。
 */
data class UserProfile(
    val id: String,
    val username: String,
    val realName: String,
    val employeeId: String,
    val role: String,
    val stationName: String?,
) {
    val avatarInitial: String = realName.firstOrNull()?.toString() ?: "?"
    val roleLabel: String = when (role) {
        "inspector" -> "查验员"
        "supervisor" -> "监管员"
        "reviewer" -> "复核员"
        "admin" -> "管理员"
        else -> role
    }
}
