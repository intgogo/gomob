package io.gomob.feature.message

import io.gomob.model.message.StationContact

data class ContactProfileUi(
    val id: String,
    val name: String,
    val initials: String,
    val roleTitle: String,
    val specialty: String,
    val employeeId: String,
    val availabilityText: String,
    val organization: String,
    val online: Boolean,
    val peerUserId: Long?,
) {
    val avatarSeed: String get() = "contact-$id-$name"
}

internal fun HelpExpertRowUi.toContactProfileUi(): ContactProfileUi = ContactProfileUi(
    id = "expert-$userId",
    name = name,
    initials = initials,
    roleTitle = roleTitle,
    specialty = specialty,
    employeeId = employeeId,
    availabilityText = availabilityText,
    organization = "外部专家 · 协作池",
    online = availabilityText == "可发消息",
    peerUserId = userId,
)

internal fun StationContact.toContactProfileUi(): ContactProfileUi = ContactProfileUi(
    id = "user-$userId",
    name = name,
    initials = initialsFor(name),
    roleTitle = role.toRoleTitleLabel(),
    specialty = stationName.ifBlank { "本站" },
    employeeId = employeeId,
    availabilityText = "可发消息",
    organization = stationName.ifBlank { "本站" },
    online = true,
    peerUserId = userId,
)

private fun String.toRoleTitleLabel(): String = when (this) {
    "inspector" -> "查验员"
    "supervisor" -> "监管员"
    "reviewer" -> "复核员"
    "admin" -> "管理员"
    else -> this
}
