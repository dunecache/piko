/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.dataSaver

import app.crimera.patches.instagram.entity.mediadata.mediaDataEntity
import app.crimera.patches.instagram.misc.overflowMenuButton.posts.addOverflowMenuButtonAttributes
import app.crimera.patches.instagram.misc.overflowMenuButton.posts.hookOverflowMenuButton
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.EXTENDED_IMAGE_URL_CLASS
import app.crimera.patches.instagram.utils.Constants.POST_IMAGE_QUALITY_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

/**
 * The one accessor every image URL passes through on its way to the loader, and the only
 * point at which the resolution can be swapped: by the time an ImageView is bound the URL is
 * already a finished string, and `ExtendedImageUrl` exposes no setters.
 *
 * definingClass is not optional. Left unpinned, this resolved to
 * `com.instagram.api.schemas.ProfilePicUrlInfoImpl.getUrl` -- a single match, so nothing
 * complained, but an avatar schema POJO rather than the media pipeline's own URL holder. It
 * sampled only profile pictures and never touched a feed photo, which reads as "no override
 * registered" everywhere. `ExtendedImageUrl` is the per-variant type behind
 * `image_versions2`, the same class ImproveImageViewingPatch already matches on the pinned
 * version, and PhotoHider reaches feed photo URLs through the identical reflective
 * `typedurl` field + `getUrl()` shape. Pinned here so this can never silently drift onto
 * another `getUrl` again.
 */
internal object ImageUrlGetUrlFingerprint : Fingerprint(
    definingClass = EXTENDED_IMAGE_URL_CLASS,
    name = "getUrl",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
)

@Suppress("unused")
val postImageQualityPatch =
    bytecodePatch(
        name = "Per-post image quality",
        description = "Adds an Image quality entry to a post's overflow menu, letting the resolution of that post's photos be picked from Ultra, Low, Medium or original independently of the global low-resolution setting. Takes effect the next time the photo is loaded.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        // hookOverflowMenuButton is what registers the PIKO_IMAGE_QUALITY enum field. It has to
        // be a dependency rather than a separate patch: FeedButton references that field
        // directly, so a build with this patch enabled but the button patch disabled would
        // throw NoSuchFieldError the moment a post overflow menu was opened.
        // mediaDataEntity resolves the field/method-name placeholders MediaData reads
        // image variants through; without it every setForPost degrades to "no photos".
        dependsOn(settingsPatch, hookOverflowMenuButton, mediaDataEntity)
        execute {
            ImageUrlGetUrlFingerprint.method.apply {
                // Every return is rewritten, not just the first: getUrl short-circuits to null
                // on some paths and a missing return here would leave the full-resolution
                // URL intact for exactly the posts where the override is set.
                //
                // Inserted back to front so each insertion leaves the earlier indices valid.
                val returnIndices =
                    instructions.mapIndexedNotNull { index, instruction ->
                        index.takeIf { instruction.opcode == Opcode.RETURN_OBJECT }
                    }

                returnIndices.asReversed().forEach { index ->
                    val returnRegister = getInstruction(index).registersUsed[0]
                    addInstructions(
                        index,
                        """
                        invoke-static {v$returnRegister}, $POST_IMAGE_QUALITY_DESCRIPTOR->rewriteUrl(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$returnRegister
                        """.trimIndent(),
                    )
                }
            }
            enableSettings("ultraPostImageQuality")
            addOverflowMenuButtonAttributes("PIKO_IMAGE_QUALITY", "imageQualityOverflowButton")
        }
    }
