/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.actionbar;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.ProfileInfo;
import app.morphe.extension.instagram.patches.userprofile.ProfileMoreOption;
import app.morphe.extension.instagram.patches.photos.PostImageQuality;
import app.morphe.extension.instagram.patches.dm.SavedMessagesHook;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.constants.Constants;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.Logger;

import com.instagram.common.session.UserSession;

public class ActionBarPatch {

    private static final Set<ImageView> GHOST_MODE_ICONS = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Bars that already carry our quality button. The bind hook can run again for a recycled
     * row, and without this the icon would stack once per rebind.
     */
    private static final Set<ViewGroup> QUALITY_BUTTON_BARS =
            Collections.newSetFromMap(new WeakHashMap<ViewGroup, Boolean>());

    private static void updateGhostModeIcons(boolean enabled) {
        String icon = enabled ? UI.DRAWABLE_EYE_STROKE_ICON : UI.DRAWABLE_EYE_ICON;
        for (ImageView imageView : GHOST_MODE_ICONS) {
            UI.setThemedIcon(imageView, icon);
        }
    }

    private static void ghostModeToggle(ViewGroup viewGroup) throws Exception {
        if(SettingsStatus.ghostSection()){
            boolean ghostModeToggle = Pref.getTurnOnAllGhostModes();

            String iconStr = ghostModeToggle ? UI.DRAWABLE_EYE_STROKE_ICON:UI.DRAWABLE_EYE_ICON;
            ImageView imageView = UI.addImageViewToViewGroup(viewGroup, iconStr, null);
            GHOST_MODE_ICONS.add(imageView);
            imageView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        boolean ghostModeToggle= !Pref.getTurnOnAllGhostModes();
                        Pref.setTurnOnAllGhostModes(ghostModeToggle);
                        updateGhostModeIcons(ghostModeToggle);

                        String toastStr = ghostModeToggle ? str("piko_ghost_modes_on") : str("piko_ghost_modes_default");
                        Utils.showToastShort(toastStr);
                    } catch (Exception ex) {
                        Logger.printException(() -> "ghost icon click failed: ", ex);
                    }
                }
            });
        }

    }

    public static void mainFeedActionBarButton(ViewGroup viewGroup) {
        try {
            if (viewGroup == null) {
                return;
            }

            // Unconditional: distinguishes "hook never fires" from "fired but skipped".
            try {
                if (Pref.pikoDebug()) {
                    Log.d("piko", "[menu] action-bar bind "
                            + viewGroup.getClass().getName()
                            + " children=" + viewGroup.getChildCount()
                            + " qualityFlag=" + SettingsStatus.ultraPostImageQuality);
                }
            } catch (Throwable ignored) {
            }

            Set<String> pref = Pref.mainFeedActionBarButtons();

            if(pref.contains(Constants.AB_GHOST_MODE_ICON)) {
                ghostModeToggle(viewGroup);
            }

            if(pref.contains(Constants.AB_SETTINGS_ICON)) {
                UI.pikoSettingsGear(viewGroup);
            }

            if (SettingsStatus.ultraPostImageQuality && !QUALITY_BUTTON_BARS.contains(viewGroup)) {
                try {
                    if (Pref.pikoDebug()) {
                        Log.d("piko", "[menu] quality action-bar button on "
                                + viewGroup.getClass().getName());
                    }
                } catch (Throwable ignored) {
                }
                final ViewGroup bar = viewGroup;
                android.widget.ImageView added = UI.addImageViewToViewGroup(viewGroup, UI.DRAWABLE_COLLECTIONS_ICON,
                        new Runnable() {
                            @Override
                            public void run() {
                                PostImageQuality.openQualitySheet(bar);
                            }
                        });
                // Only dedup on success: a failed insert (null) must be retried on the next
                // bind, otherwise the bar is marked yet carries no button forever. A button
                // with no drawable is equally invisible, so fall back to a known-good icon.
                if (added != null) {
                    boolean hasDrawable = false;
                    try {
                        hasDrawable = added.getDrawable() != null;
                    } catch (Throwable ignored) {
                    }
                    if (!hasDrawable) {
                        try {
                            UI.setThemedIcon(added, UI.DRAWABLE_DOWNLOAD_ICON);
                            try {
                                hasDrawable = added.getDrawable() != null;
                            } catch (Throwable ignored) {
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (hasDrawable) {
                        QUALITY_BUTTON_BARS.add(viewGroup);
                    } else {
                        try {
                            viewGroup.removeView(added);
                        } catch (Throwable ignored) {
                        }
                        try {
                            if (Pref.pikoDebug()) {
                                Log.d("piko", "[menu] quality action-bar button has no drawable, will retry");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

        } catch (Exception e) {
            Logger.printException(() -> "mainFeedActionBarButton failure", e);
            PikoUtils.logger(e);
        }
    }

    public static void userProfileActionBarButton(Activity activity, ViewGroup viewGroup, UserSession userSession, Object userObject){
        try {
            if (activity == null || viewGroup == null) {
                return;
            }

            Set<String> pref = Pref.userProfileActionBarButtons();

            UserData userData = new UserData(userObject);
            Boolean isSelfProfile = userData.getUserId().equals(userSession.getUserId());

            if(pref.contains(Constants.AB_SETTINGS_ICON) && isSelfProfile) {
                UI.pikoSettingsGear(viewGroup);
            }

            if(pref.contains(Constants.AB_GHOST_MODE_ICON) && isSelfProfile) {
                ghostModeToggle(viewGroup);
            }

            if(pref.contains(Constants.AB_PROFILE_INFO_ICON)) {
                UI.addImageViewToViewGroup(viewGroup, UI.DRAWABLE_INFO_ICON, () -> ProfileMoreOption.moreOptionsDailogueBox(activity, userData));
            }


        } catch (Exception e) {
            Logger.printException(() -> "userProfileActionBarButton: ", e);
            PikoUtils.logger(e);
        }
    }

    public static void chatActionBarButton(ViewGroup viewGroup) {
        try {
            if (viewGroup == null) {
                return;
            }

            Set<String> pref = Pref.chatActionBarButtons();

            if(pref.contains(Constants.AB_SETTINGS_ICON)) {
                UI.pikoSettingsGear(viewGroup);
            }

            if(pref.contains(Constants.AB_GHOST_MODE_ICON)) {
                ghostModeToggle(viewGroup);
            }

            if(SettingsStatus.saveDeletedMessages) {
                Context context = viewGroup.getContext();
                UI.addImageViewToViewGroup(viewGroup, UI.DRAWABLE_HISTORY_ICON,
                        () -> SavedMessagesHook.openDeletedMessages(context));
            }

        } catch (Exception e) {
            Logger.printException(() -> "chatActionBarButton:", e);
        }
    }

    public static void inboxActionBarButton(ViewGroup viewGroup) {
        try {
            if (viewGroup == null) {
                return;
            }

            Set<String> pref = Pref.inboxActionBarButtons();

            if(pref.contains(Constants.AB_SETTINGS_ICON)) {
                UI.pikoSettingsGear(viewGroup);
            }

            if(pref.contains(Constants.AB_GHOST_MODE_ICON)) {
                ghostModeToggle(viewGroup);
            }

        } catch (Exception e) {
            Logger.printException(() -> "inboxActionBarButton:", e);
        }
    }

}
