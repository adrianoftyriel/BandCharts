package org.bandcharts.app.data

import kotlinx.serialization.Serializable
import org.bandcharts.app.input.FootSwitchMap
import org.bandcharts.app.ui.viewer.ViewerPreferences
import org.bandcharts.update.UpdateChannel

/**
 * Which colours the app draws itself in.
 *
 * [SYSTEM] is not the default. A phone set to light because that is what its
 * owner wants at a desk is still the phone that will be on a stand in a dark
 * room pointed at an audience, and being readable there is this app's job
 * rather than being consistent with the launcher. Somebody who wants the system
 * setting followed can say so, which is what this is for.
 */
@Serializable
enum class ThemeChoice { SYSTEM, LIGHT, DARK }

@Serializable
data class AppSettings(
    val viewer: ViewerPreferences = ViewerPreferences(),
    val footSwitch: FootSwitchMap = FootSwitchMap(),
    /** What this device calls itself in a session. Defaults to the model name. */
    val deviceName: String = "",
    /** Stable per-install id, so a reconnecting device is recognised as itself. */
    val deviceId: String = "",
    val lastSessionName: String = "",
    val autoJoinLastSession: Boolean = false,
    /** Index the contents of text charts on import, for search and key detection. */
    val indexChartContents: Boolean = true,
    val theme: ThemeChoice = ThemeChoice.DARK,
    /**
     * Which builds the in-app updater will offer.
     *
     * Pre-releases by default, because at the moment they are the only thing
     * published: every push to `dev` cuts one and there has never been a full
     * release. Defaulting to releases-only would ship an update feature that
     * correctly and permanently reports that there is nothing to install.
     */
    val updateChannel: UpdateChannel = UpdateChannel.PRERELEASE,

    /** The band's ChartServe, once paired. Empty means "not set up". */
    val chartServeUrl: String = "",
    /** This device's own token, from pairing. Never the admin token - see [org.bandcharts.app.data.ChartServeClient]. */
    val chartServeToken: String = "",
    /** This device's id on that server, so it can be recognised in `/v1/devices`. */
    val chartServeDeviceId: String = "",
)
