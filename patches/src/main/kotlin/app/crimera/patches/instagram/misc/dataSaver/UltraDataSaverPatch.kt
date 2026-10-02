/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dataSaver

import app.crimera.patches.instagram.links.interceptUriPatch
import app.crimera.patches.instagram.misc.disableVideoAutoplay.disableVideoAutoplayPatch
import app.crimera.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.crimera.patches.instagram.misc.improveImageViewing.improveImageViewingPatch
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.addFlags
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val ultraDataSaverPatch =
    bytecodePatch(
        name = "Ultra data saver",
        description = "Master data-saving mode for limited connections: blocks feed and Reels autoplay, stories and explore prefetch, and loads low-resolution images. Each part can be toggled in Piko settings, with optional auto-enable on metered connections.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(
            settingsPatch,
            hookFlagsPatch,
            interceptUriPatch,
            disableVideoAutoplayPatch,
            improveImageViewingPatch,
        )
        execute {
            enableSettings("ultraDataSaver")
            addFlags("ultraDataSaverFlags")
        }
    }
