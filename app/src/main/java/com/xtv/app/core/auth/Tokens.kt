package com.xtv.app.core.auth

/** OAuth token response kept internal to the refresh/provisioning adapters. */
data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
)
