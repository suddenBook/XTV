package com.xtv.app

/**
 * Which credential the setup screen should ask for.
 *
 * The screen has to name it. An install provisioned before the bearer became mandatory still holds a
 * client id and a live session, so telling that user "this install has no credentials yet" would be
 * both false and unactionable.
 */
enum class MissingCredential { CLIENT_ID, BEARER, SESSION, PROVISIONING, PRIVATE_STATE }

/** Where a launch lands. */
sealed interface Start {
    data class NeedsSetup(val missing: MissingCredential) : Start
    /** Offline fixture playback: no credentials, no token, no spend. */
    data class Fixture(val name: String) : Start
    data object Home : Start
}

/**
 * Decides the first screen from state alone.
 *
 * Pulled out of the activity deliberately. The one bug this app has actually shipped in this area —
 * a freshly provisioned install stranded on the setup screen — was a bug in exactly this decision,
 * and while it lived inside a `LaunchedEffect` it could only be checked by reinstalling on a TV and
 * looking at the panel. Here it is five lines of pure logic with a test per branch.
 *
 * Order matters. The fixture path is checked first because it is the one route that neither needs
 * nor spends credentials; gating it behind them made the documented offline-playback command
 * unusable on any build without a compiled-in client id, which is every published build.
 */
fun decideStart(
    clientId: String?,
    bearer: String?,
    fixture: String?,
    hasSession: Boolean,
): Start = when {
    fixture != null -> Start.Fixture(fixture)
    clientId.isNullOrBlank() -> Start.NeedsSetup(MissingCredential.CLIENT_ID)
    bearer.isNullOrBlank() -> Start.NeedsSetup(MissingCredential.BEARER)
    !hasSession -> Start.NeedsSetup(MissingCredential.SESSION)
    else -> Start.Home
}
