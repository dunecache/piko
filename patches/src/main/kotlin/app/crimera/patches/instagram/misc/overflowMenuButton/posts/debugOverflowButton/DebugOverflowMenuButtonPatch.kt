/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.overflowMenuButton.posts.debugOverflowButton

import app.crimera.patches.instagram.misc.overflowMenuButton.posts.addOverflowMenuButtonAttributes
import app.crimera.patches.instagram.misc.overflowMenuButton.posts.hookOverflowMenuButton
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val debugOverflowMenuButtonPatch =
    bytecodePatch(
        description = "Adds debug overflow menu button",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookOverflowMenuButton)
        execute {

            addOverflowMenuButtonAttributes("PIKO_DEBUG", "debugOverflowButton")
            // The enum field only exists once this patch is applied, but hookOverflowMenuButton
            // is pulled in by several other patches, and FeedButton reads PIKO_DEBUG on every
            // post menu. Exposing a flag lets those references be guarded, which they were not:
            // turning the debug setting on without this patch threw NoSuchFieldError.
            enableSettings("pikoDebugOverflowButton")
        }
    }
