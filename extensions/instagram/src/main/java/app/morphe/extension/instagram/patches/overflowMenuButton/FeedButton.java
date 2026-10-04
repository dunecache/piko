/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.instagram.patches.overflowMenuButton;

import static app.morphe.extension.instagram.utils.IgStr.str;

import java.util.ArrayList;
import java.util.List;
import android.content.Context;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.crimera.ObjectBrowser;

import app.morphe.extension.instagram.patches.Links;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.entity.Entity;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.patches.feed.MoreOptionsOnPostPatch;
import app.morphe.extension.instagram.patches.photos.PostImageQuality;
import app.morphe.extension.instagram.settings.ActivityHook;

import com.instagram.feed.media.mediaoption.MediaOption$Option;
import com.instagram.common.session.UserSession;

public class FeedButton {

    private static final String TAG = "piko";

    /** Debug line, gated on the Piko debug setting so release builds stay quiet. */
    private static void logDebug(String message) {
        try {
            if (!Pref.pikoDebug()) return;
            Log.d(TAG, "[menu] " + message);
        } catch (Throwable ignored) {
        }
    }

    private static MediaOption$Option initOverflowButton(String tag, int randomIndex, String drawableResName){
        int drawableIconId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE,drawableResName);
        return new MediaOption$Option(tag, randomIndex, drawableIconId);
    }

    public static MediaOption$Option[] addToMenuOptionArray() {
        MediaOption$Option[] originalArray = MediaOption$Option.$values();
        List<MediaOption$Option> additionalButtonsList = new ArrayList<>();

        if(SettingsStatus.downloadMedia){
            additionalButtonsList.add(MediaOption$Option.PIKO_DOWNLOAD);
        }
        if(SettingsStatus.moreOptionsOnPost){
            additionalButtonsList.add(MediaOption$Option.PIKO_MORE_POST_OPTION);
        }
        if(SettingsStatus.downloadWithExternalDownloader){
            additionalButtonsList.add(MediaOption$Option.PIKO_EXTERNAL_DOWNLOADER);
        }
        if(SettingsStatus.ultraPostImageQuality){
            additionalButtonsList.add(MediaOption$Option.PIKO_IMAGE_QUALITY);
        }

        int additionalButtonListSize = additionalButtonsList.size();

        if(additionalButtonListSize > 0) {
            int originalLength = originalArray.length;

            // Create a new array that can hold the existing value as well as newly added buttons.
            MediaOption$Option[] newButtonArray = new MediaOption$Option[additionalButtonListSize + originalLength];

            // Copy elements from the old array to the new array.
            System.arraycopy(originalArray, 0, newButtonArray, 0, originalLength);

            for(MediaOption$Option button : additionalButtonsList){
                newButtonArray[originalLength++] = button;
            }
            return newButtonArray;
        }
        // Returns the original array as a fallback.
        return originalArray;
    }


    /**
     * Build-time hint for the row-appender name, patched by HookOverflowMenuButton to the real
     * name for the pinned IG version. Kept as a hint only: {@link #findAdderMethod} falls back
     * to a signature scan, so a rename without a patch update still resolves.
     *
     * <p>Kept as a dedicated method (rather than a field initializer) so the patcher's
     * string replacement has a single unambiguous literal to rewrite.
     */
    private static String adderMethodHint() {
        return "A00";
    }
    private static volatile String ADDER_METHOD_NAME = null;

    private static String adderMethodName() {
        String hint = ADDER_METHOD_NAME;
        if (hint != null) return hint;
        try {
            hint = adderMethodHint();
        } catch (Throwable ignored) {
            hint = "A00";
        }
        ADDER_METHOD_NAME = hint;
        return hint;
    }

    private static Class<?> getEnumButtonClass() throws Exception {
        try {
            return Class.forName("X.6zl");
        } catch (ClassNotFoundException e) {
            // Patched name went stale (obfuscated name changes per IG bump). Derive it from
            // the row appender's first parameter instead of giving up.
            Method adder = findAdderMethodBySignature(Object.class);
            if (adder != null) return adder.getParameterTypes()[0];
            throw e;
        }
    }
    private static Object enumNormalButton(Class<?> enumBtnClass) throws Exception {
        return (Object) new Entity().getMethod(enumBtnClass, "valueOf", "NORMAL");
    }
    private static Object enumNormalButton() throws Exception {
        return enumNormalButton(getEnumButtonClass());
    }

    private static void addButton(MediaOption$Option overflowButton, String overflowButtonText, Object buttonAdderObject, ArrayList buttonlist) throws Exception {
        if (buttonAdderObject == null || buttonlist == null) {
            logDebug("addButton skipped, adder=" + buttonAdderObject + " list=" + (buttonlist == null ? "null" : "size=" + buttonlist.size()));
            return;
        }
        Class<?> clazz = buttonAdderObject.getClass();

        // getDeclaredMethod only sees the exact runtime class. The menu builder is final on the
        // pinned version so this normally hits first try, but if the object ever arrives as a
        // subclass (or a wrapper), an exact lookup throws NoSuchMethodException and the row
        // silently never appears -- which is exactly the failure this logging is here to catch.
        Method method = findAdderMethod(clazz);

        method.setAccessible(true);
        Class<?> enumBtnClass = method.getParameterTypes()[0];
        method.invoke(null, enumNormalButton(enumBtnClass), overflowButton, buttonAdderObject, overflowButtonText, buttonlist, false);
        logDebug("added row '" + overflowButtonText + "' via " + clazz.getName() + "." + method.getName());
    }

    /**
     * The menu row appender, looked up by walking up the class hierarchy. The parameter list
     * tracks each level because the third parameter is declared as the defining class itself.
     *
     * <p>Tries the patch-provided {@link #ADDER_METHOD_NAME} first, then falls back to a
     * signature scan so an obfuscated rename alone cannot remove every Piko row.
     */
    private static Method findAdderMethod(Class<?> clazz) throws Exception {
        Class<?> enumBtnClass = null;
        try {
            enumBtnClass = getEnumButtonClass();
        } catch (Exception ignored) {
        }
        String hintedName = "?";
        try {
            hintedName = adderMethodName();
        } catch (Throwable ignored) {
        }
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            if (enumBtnClass != null) {
                try {
                    return c.getDeclaredMethod(
                            hintedName,
                            enumBtnClass,
                            MediaOption$Option.class,
                            c,
                            CharSequence.class,
                            ArrayList.class,
                            boolean.class
                    );
                } catch (NoSuchMethodException ignored) {
                    // Fall through to the signature scan below.
                }
            }
            Method scanned = scanAdderSignature(c);
            if (scanned != null) return scanned;
        }
        // Last resort: hierarchy-wide scan without requiring the enum class up front
        // (covers the case where both the name and the enum class went stale).
        Method loose = findAdderMethodBySignature(clazz);
        if (loose != null) return loose;
        throw new NoSuchMethodException(hintedName + " row appender on " + clazz.getName());
    }

    /** Signature match on a single class: static, 6 params, (enum, Option, self, text, list, flag). */
    private static Method scanAdderSignature(Class<?> c) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 6) continue;
                if (!p[1].equals(MediaOption$Option.class)) continue;
                if (!p[3].isAssignableFrom(CharSequence.class) && !CharSequence.class.isAssignableFrom(p[3])) continue;
                if (!java.util.List.class.isAssignableFrom(p[4]) && !p[4].isAssignableFrom(ArrayList.class)) continue;
                if (!p[5].equals(boolean.class) && !p[5].equals(Boolean.TYPE)) continue;
                if (!p[0].isEnum()) continue;
                try {
                    if (!p[2].isAssignableFrom(c) && !c.isAssignableFrom(p[2])) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                return m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Hierarchy-wide signature scan that does not need the enum class up front. */
    private static Method findAdderMethodBySignature(Class<?> clazz) {
        try {
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                Method m = scanAdderSignature(c);
                if (m != null) return m;
            }
            // The adder may live on an unrelated helper class when the receiver is a wrapper:
            // scan the receiver's methods' declaring classes is already covered above, so also
            // try interfaces as a final pass.
            for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Class<?> itf : c.getInterfaces()) {
                    Method m = scanAdderSignature(itf);
                    if (m != null) return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static MediaOption$Option downloadOverflowButton(){
        return FeedButton.initOverflowButton("PIKO_DOWNLOAD", 500, UI.DRAWABLE_DOWNLOAD_ICON);
    }

    public static MediaOption$Option morePostOptionOverflowButton(){
        return FeedButton.initOverflowButton("PIKO_MORE_POST_OPTION", 501, UI.DRAWABLE_BLUB_ICON);
    }

    public static MediaOption$Option debugOverflowButton(){
        return FeedButton.initOverflowButton("PIKO_DEBUG", 502, UI.DRAWABLE_DEBUG_ICON);
    }

    public static MediaOption$Option externalDownloaderOverflowButton(){
        return FeedButton.initOverflowButton("PIKO_EXTERNAL_DOWNLOADER", 503, UI.DRAWABLE_DOWNLOAD_ICON);
    }

    public static MediaOption$Option imageQualityOverflowButton(){
        return FeedButton.initOverflowButton("PIKO_IMAGE_QUALITY", 504, UI.DRAWABLE_COLLECTIONS_ICON);
    }


    private static void addDownloadButton(Object buttonAdderObject, ArrayList buttonlist) throws Exception {
        String DOWNLOAD_BUTTON_TEXT = str("piko_download_options");
        if(Pref.enableDirectDownload()){
            DOWNLOAD_BUTTON_TEXT = str("piko_category_download_media");
        }
        addButton(MediaOption$Option.PIKO_DOWNLOAD, DOWNLOAD_BUTTON_TEXT, buttonAdderObject, buttonlist);
    }

    private static void tryAddRow(String tag, RowAdder adder) {
        try {
            adder.add();
        } catch (Exception e) {
            // One failing row must not remove every other Piko row.
            logDebug("row '" + tag + "' failed: " + e);
            Logger.printException(() -> "Error at addFeedOverflowButton/" + tag, e);
        }
    }

    private interface RowAdder {
        void add() throws Exception;
    }

    public static void addFeedOverflowButton(Object buttonAdderObject, ArrayList buttonlist){
        try {
            logDebug("addFeedOverflowButton adder="
                    + (buttonAdderObject == null ? "null" : buttonAdderObject.getClass().getName())
                    + " list=" + (buttonlist == null ? "null" : "size=" + buttonlist.size())
                    + " qualityFlag=" + SettingsStatus.ultraPostImageQuality);
            if(Pref.pikoDebug()){
                tryAddRow("debug", () -> addButton(MediaOption$Option.PIKO_DEBUG, str("piko_debug"), buttonAdderObject, buttonlist));
            }
            if(Pref.enableDownload()) {
                tryAddRow("download", () -> addDownloadButton(buttonAdderObject, buttonlist));
            }
            if(Pref.downloadWithExternalDownloader()) {
                tryAddRow("external", () -> addButton(MediaOption$Option.PIKO_EXTERNAL_DOWNLOADER, str("piko_download_with_external_downloader"), buttonAdderObject, buttonlist));
            }
            if(Pref.moreOptionsOnPost()) {
                tryAddRow("moreOptions", () -> addButton(MediaOption$Option.PIKO_MORE_POST_OPTION, str("piko_more_options"), buttonAdderObject, buttonlist));
            }
            if (SettingsStatus.ultraPostImageQuality) {
                tryAddRow("quality", () -> addButton(MediaOption$Option.PIKO_IMAGE_QUALITY, str("piko_post_quality_title"), buttonAdderObject, buttonlist));
            } else {
                logDebug("image quality row skipped, ultraPostImageQuality flag is off");
            }
        } catch (Exception e) {
            Logger.printException(() -> "Error at addFeedOverflowButton",e);
        }
    }

    public static boolean isCustomButtonPressed(MediaOption$Option pressedButton){
        return (
                pressedButton.equals(MediaOption$Option.PIKO_DEBUG) ||
                (SettingsStatus.downloadMedia && pressedButton.equals(MediaOption$Option.PIKO_DOWNLOAD)) ||
                (SettingsStatus.moreOptionsOnPost && pressedButton.equals(MediaOption$Option.PIKO_MORE_POST_OPTION)) ||
                (SettingsStatus.downloadWithExternalDownloader && pressedButton.equals(MediaOption$Option.PIKO_EXTERNAL_DOWNLOADER)) ||
                (SettingsStatus.ultraPostImageQuality && pressedButton.equals(MediaOption$Option.PIKO_IMAGE_QUALITY))
        );
    }

    public static void customButtonOnClick(MediaOption$Option pressedButton, UserSession userSession, Context context, Object mediaObject, int currentMediaIndex){
        try{
            if(pressedButton.equals(MediaOption$Option.PIKO_DEBUG)) {
                ObjectBrowser.browseObject(context, new MediaData(mediaObject, userSession));

            } else if (SettingsStatus.downloadMedia && pressedButton.equals(MediaOption$Option.PIKO_DOWNLOAD)) {
                DownloadUtils.downloadPost(context, userSession, mediaObject, currentMediaIndex);

            } else if (SettingsStatus.moreOptionsOnPost && pressedButton.equals(MediaOption$Option.PIKO_MORE_POST_OPTION)) {
                MoreOptionsOnPostPatch.postMoreOptions(context, userSession, mediaObject, currentMediaIndex);

            } else if (SettingsStatus.downloadWithExternalDownloader && pressedButton.equals(MediaOption$Option.PIKO_EXTERNAL_DOWNLOADER)) {
                DownloadUtils.externalDownloader(mediaObject,currentMediaIndex);

            } else if (SettingsStatus.ultraPostImageQuality && pressedButton.equals(MediaOption$Option.PIKO_IMAGE_QUALITY)) {
                PostImageQuality.showQualityDialog(context, mediaObject, userSession, currentMediaIndex);

            }

        } catch (Exception e) {
            Logger.printException(() -> "Error at customButtonOnClick",e);
        }
    }

}