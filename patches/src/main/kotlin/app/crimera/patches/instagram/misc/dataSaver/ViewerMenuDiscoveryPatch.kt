/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dataSaver

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.VIEWER_MENU_DISCOVERY_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * The base activity every Instagram screen extends, and the only hook needed to see them all.
 *
 * Already fingerprinted for the settings patch, so its presence on the pinned version is
 * established rather than assumed.
 */
internal object IgFragmentActivityOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    definingClass = "Lcom/instagram/base/activity/IgFragmentActivity;",
)

/**
 * Temporary aid for locating the post viewer's overflow menu, which is the one thing blocking a
 * per-post quality entry outside the feed.
 *
 * Reports each activity class and any menu or option typed field it holds. All output is behind
 * the debug setting. This is meant to be patched, logged once, read, and then replaced by a
 * real patch built from what it prints — it makes no behavioural change.
 */
@Suppress("unused")
val viewerMenuDiscoveryPatch =
    bytecodePatch(
        name = "Post viewer menu discovery",
        description = "Diagnostic only. With the debug setting on, logs each activity class and its menu or option typed fields, to identify the post viewer's overflow menu. Makes no behavioural change.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        execute {
            IgFragmentActivityOnCreateFingerprint.method.addInstructions(
                0,
                """
                invoke-static {p0}, $VIEWER_MENU_DISCOVERY_DESCRIPTOR->onActivityCreated(Landroid/app/Activity;)V
                """.trimIndent(),
            )
        }
    }
