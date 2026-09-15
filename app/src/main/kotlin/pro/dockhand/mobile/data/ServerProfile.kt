package pro.dockhand.mobile.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val allowCleartext: Boolean = false
)
