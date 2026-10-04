/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.distractionFree

import app.crimera.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PREF_CALL_DESCRIPTOR
import app.crimera.patches.instagram.utils.addFlags
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel

internal object FeedAutoScrollFingerprint : Fingerprint(
    strings = listOf("enable_in_session_auto_scroll", "enable_warm_start_auto_scroll"),
    returnType = "Z",
)

internal object FeedInSessionRefreshFingerprint : Fingerprint(
    strings = listOf("enable_auto_refresh_by_instruction", "enable_in_session_refresh_by_instruction"),
    returnType = "Z",
)

@Suppress("unused")
val disableFeedAutoRefreshPatch =
    bytecodePatch(
        name = "Disable feed auto refresh",
        description = "Prevents main home feed from auto-refreshing and auto-scrolling to top, and suppresses the New posts banner. Manual pull-to-refresh still works.",
    ) {
        dependsOn(settingsPatch, hookFlagsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // Blocks automatic scroll-to-top triggers (warm start / in-session).
            // Manual scrolling and pull-to-refresh use different code paths and are preserved.
            FeedAutoScrollFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    $PREF_CALL_DESCRIPTOR->disableFeedAutoRefresh()Z
                    move-result v0
                    if-eqz v0, :piko
                    const/4 v0, 0x0
                    return v0
                    """.trimIndent(),
                    ExternalLabel("piko", getInstruction(0)),
                )
            }

            // Blocks server-instructed in-session auto refresh.
            // Manual pull-to-refresh (ptr_rate_limit / ptr_trigger_distance) is untouched.
            FeedInSessionRefreshFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    $PREF_CALL_DESCRIPTOR->disableFeedAutoRefresh()Z
                    move-result v0
                    if-eqz v0, :piko
                    const/4 v0, 0x0
                    return v0
                    """.trimIndent(),
                    ExternalLabel("piko", getInstruction(0)),
                )
            }

            addFlags("feedAutoRefreshFlags")
            enableSettings("disableFeedAutoRefresh")
        }
    }
