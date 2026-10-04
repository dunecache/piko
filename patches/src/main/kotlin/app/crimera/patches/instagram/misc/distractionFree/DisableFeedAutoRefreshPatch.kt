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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import java.util.logging.Logger

// Blocks automatic scroll-to-top triggers (warm start / in-session).
private val AUTO_SCROLL_FLAG_NAMES =
    listOf(
        "enable_warm_start_auto_scroll",
        "enable_in_session_auto_scroll",
    )

// Blocks server-instructed in-session auto refresh.
// Manual pull-to-refresh (ptr_rate_limit / ptr_trigger_distance) is untouched.
private val AUTO_REFRESH_FLAG_NAMES =
    listOf(
        "enable_auto_refresh_by_instruction",
        "enable_in_session_refresh_by_instruction",
    )

private val FEED_AUTO_REFRESH_LOGGER = Logger.getLogger("DisableFeedAutoRefreshPatch")

// Parameters occupy the last registers of a method, so this is the first register
// that cannot hold an argument and is therefore safe to clobber at index zero.
private val Method.firstLocalRegister: Int
    get() {
        val parameterRegisters = parameterTypes.size + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
        val registerCount = implementation?.registerCount ?: parameterRegisters

        return (registerCount - parameterRegisters).coerceAtMost(registerCount - 1)
    }

private fun Method.hookFeedAutoRefreshFlag() {
    val register = firstLocalRegister

    if (register !in 0..14) {
        FEED_AUTO_REFRESH_LOGGER.warning("Skipped hooking $definingClass->$name: no usable register")
        return
    }

    addInstructionsWithLabels(
        0,
        """
        $PREF_CALL_DESCRIPTOR->disableFeedAutoRefresh()Z
        move-result v$register
        if-eqz v$register, :piko
        const/4 v$register, 0x0
        return v$register
        """.trimIndent(),
        ExternalLabel("piko", getInstruction(0)),
    )
}

@Suppress("unused")
val disableFeedAutoRefreshPatch =
    bytecodePatch(
        name = "Disable feed auto refresh",
        description = "Prevents main home feed from auto-refreshing and auto-scrolling to top, and suppresses the New posts banner. Manual pull-to-refresh still works.",
    ) {
        dependsOn(settingsPatch, hookFlagsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // Flag lookups are best-effort, these names are only present as literals in
            // some builds. Behaviour still comes from the `ig_android_feed_refresh_fbid` and
            // `ig_android_refresh_instruction_from_server` overrides in `hookFlagsPatch`.
            val flagGetters = LinkedHashMap<String, Method>()

            for (flagName in AUTO_SCROLL_FLAG_NAMES + AUTO_REFRESH_FLAG_NAMES) {
                val method =
                    runCatching {
                        Fingerprint(
                            strings = listOf(flagName),
                            returnType = "Z",
                        ).matchOrNull()?.method
                    }.onFailure {
                        FEED_AUTO_REFRESH_LOGGER.fine("Failed to match $flagName: ${it.message}")
                    }.getOrNull()

                if (method == null) {
                    FEED_AUTO_REFRESH_LOGGER.fine("No boolean getter found for $flagName")
                    continue
                }

                flagGetters.putIfAbsent("${method.definingClass}->${method.name}${method.parameterTypes}", method)
            }

            flagGetters.values.forEach { it.hookFeedAutoRefreshFlag() }

            if (flagGetters.isEmpty()) {
                FEED_AUTO_REFRESH_LOGGER.warning("No feed auto refresh flag getters matched, using config flag overrides only")
            }

            addFlags("feedAutoRefreshFlags")
            enableSettings("disableFeedAutoRefresh")
        }
    }
