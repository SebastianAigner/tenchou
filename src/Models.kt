package io.sebi.tenchou

import kotlinx.serialization.Serializable

@Serializable
data class StoredApp(
    val id: String,
    val title: String,
    val subtitle: String,
    val bundleId: String,
    val version: String,
    val build: String,
    val uploadedAt: String,
    val signedUntil: String? = null,
    val provisioningProfile: String? = null,
)

@Serializable
data class AppSummary(
    val id: String,
    val title: String,
    val subtitle: String,
    val bundleId: String,
    val version: String,
    val build: String,
    val uploadedAt: String,
    val signedUntil: String? = null,
    val iconUrl: String,
    val installUrl: String,
)

@Serializable
data class BuildReservation(val build: String)

@Serializable
data class ApiError(val error: String)

data class InspectedIpa(
    val bundleId: String,
    val displayName: String,
    val version: String,
    val build: String,
    val signedUntil: String?,
    val provisioningProfile: String?,
)
