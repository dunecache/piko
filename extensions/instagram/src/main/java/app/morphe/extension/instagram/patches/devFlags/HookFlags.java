/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */


package app.morphe.extension.instagram.patches.devFlags;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;

import android.util.Log;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.entity.DeveloperOptions;
import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.entity.DeveloperOptionsItem;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.settings.SettingsStatus;

public class HookFlags {
    private static Map<String, Boolean> BOOL_FLAGS = new HashMap<>();
    private static Map<String, Long> LONG_FLAGS = new HashMap<>();
    private static DeveloperOptions developerOptions = new DeveloperOptions();

    /**
     * Server flags that make Reels spend data on their own, forced off while the Ultra data
     * saver's Reels part is on. All four preload or autoplay video, and all four fail safe:
     * turning them off can only stop a download, never start one.
     *
     * IDs verified against docs/mappings/439.0.0.37.89.json and stable across 426, 430 and
     * 435. Keyed as universalId::paramId, matching DeveloperOptionsItem.getConfigId().
     *
     * Note the neighbouring fields of 96015 are inverted ({@code disable_preload_on_first_reel},
     * {@code disable_preload_on_tap_stories}) — writing false there would ENABLE preloading,
     * so they are deliberately absent.
     */
    private static final Set<String> ULTRA_REELS_KILL_FLAGS = Set.of(
            "96015::0",    // android_video_playback_reels_preload::enable_adjacent_video_preload
            "119702::0",   // ig4a_clips_video_player::enable_adjacent_player_warmup
            "56394::69",   // ig_android_direct_infra::run_reel_preload_bg
            "117144::3"    // ig_search_tentpole::android_enable_reels_autoplay
    );

    /**
     * Server flags that make the app download media with no screen open to show it. Forced on
     * while the Ultra data saver's background prefetch part is on, because every one of them is
     * named {@code disable_*} and therefore takes the opposite direction from the Reels set.
     *
     * <p>Deliberately excludes {@code 60096::0} (ig_android_disable_bg_prefetch), despite it
     * being the broadest killswitch available and stable across all four mapped versions. It is
     * named generically rather than for the feed, and Instagram demonstrably puts unrelated
     * features under one experiment: {@code 56394::69} above lives in {@code
     * ig_android_direct_infra}, the DM experiment, while controlling Reel preloading. So an
     * experiment's name is no evidence of its scope, and this set is restricted to flags that
     * name the feed outright.
     *
     * <p>If {@link #WATCHED_FLAGS} shows any of these being read from inside a DM thread, drop
     * that one rather than relaxing the whole set.
     */
    private static final Set<String> ULTRA_BG_PREFETCH_KILL_FLAGS = Set.of(
            "75561::30",   // ig_android_feed_background_prefetch_fbid::disable_media_prefetch
            "46244::30",   // ig_android_launcher_mainfeed_background_prefetch::disable_feed_bg_prefetch_job
            "86979::80"    // ig4a_delivery_homecoming::disable_homecoming_feed_prefetch
    );

    /**
     * Flags whose reads are counted under the debug setting.
     *
     * <p>This exists to answer a question the mappings cannot: Instagram groups unrelated
     * features under a single experiment ID, so the only way to know whether a prefetch flag is
     * read from inside a DM thread is to watch it being read. Open DMs with debug on and the log
     * shows which of these the messaging code paths actually consult.
     */
    private static final Set<String> WATCHED_FLAGS = Set.of(
            "60096::0",    // ig_android_disable_bg_prefetch (excluded from the set above)
            "46244::30",
            "75561::30",
            "86979::80",
            "82544::21",   // ig4a_direct_thread_prefetch_optimizations::enable_video_prefetch
            "94658::19",   // ig4a_direct_thread_to_clips_viewer_prefetch::disable_prefetch_clips_medias
            "77351::100",  // ig_android_direct_cache::prefetch_message_media_in_second_page
            "26104::6"     // ig_android_direct_inbox_snapshot_limits::scrolling_prefetch_distance
    );

    /** How many distinct watched flags to report before the log gets noisy. */
    private static final int WATCH_LOG_LIMIT = 24;

    private static final Map<String, Boolean> WATCH_HITS =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(32, 0.75f, true));

    private static void simpleOverflowMenuFlags() {
        BOOL_FLAGS.put("104772", false); //ig_ini
        BOOL_FLAGS.put("117613::0", true); //ig_overflow_menu_icon::use_more_lines_icon
        BOOL_FLAGS.put("100002", true); //ig_igds_android_prism_overflow_sheet
    }

    private static void adsFlags() {
//        BOOL_FLAGS.put("58206::0", false); //is_acp_enabled
//        BOOL_FLAGS.put("72396::0", false); //is_mae_exclusion_feed_enabled
//        BOOL_FLAGS.put("78046::0", false); //is_mae_exclusion_feed_enabled
//        BOOL_FLAGS.put("78046::9", false); //enable_no_invalidation_reason_for_mae_exclusion
//        BOOL_FLAGS.put("79181::0", false); //ig_reels_ads_1x2_explore_halc_android::is_enabled
        BOOL_FLAGS.put("110800::0", false); //ig_android_controller_migration::use_v2_controller Removed in version 435.0.0.0.2
        BOOL_FLAGS.put("114983", false); //ig_stories_restyle_midcard
        BOOL_FLAGS.put("95150", false); //ig_stories_music_midcard
        BOOL_FLAGS.put("84366::12", false); //ig_stories_ayt_midcard::enable_add_yours
        BOOL_FLAGS.put("120110", false); //ig_android_scroll_break
        BOOL_FLAGS.put("105778", false); //ig_android_restyle_post_cap_promo_dialog
    }

    // Thanks to @brosssh
    private static void suggestedContentFlags() {
        BOOL_FLAGS.put("111509::3", false); //ig_search_ta_nullstate_suggestions::is_android_enabled
        BOOL_FLAGS.put("82771::0", false); //igx_foundation_litho_stories_tray::is_litho_stories_tray_enabled
//        BOOL_FLAGS.put("109730", false); //ig_android_ai_discovery_menu
//        BOOL_FLAGS.put("80654", false); //ig_meta_ai_cdd_reels_viewer
    }

    private static void profileActionBarFlags() {
        Set<String> pref = Pref.userProfileActionBarButtons();
        if (!pref.equals(Set.of(Constants.AB_CREATE))) {
            BOOL_FLAGS.put("81826::0", true); //igx_action_bar_service_replacement::is_profile_replaced
            BOOL_FLAGS.put("89230::0", true); //ig_android_profile_overflow_menu_redesign_launcher:enabled
        }
    }

    private static void mainFeedActionBarFlags() {
        Set<String> pref = Pref.mainFeedActionBarButtons();
        if (!pref.equals(Set.of(Constants.AB_CREATE, Constants.AB_NOTIFICATIONS))) {
            BOOL_FLAGS.put("81826::1", true); //igx_action_bar_service_replacement::is_main_feed_replaced
            BOOL_FLAGS.put("81826::4", true); //igx_action_bar_service_replacement::is_main_feed_large_screen_replaced
        }
    }

    private static void employeeOptionsFlags() {
        if(Pref.enableEmployeeOptions()){
            BOOL_FLAGS.put("28538::0", true); //ig_android_employee_options::is_enabled
        }else{
            BOOL_FLAGS.put("28538::0", false); //ig_android_employee_options::is_enabled
        }
    }

    private static void addRecommendedFlags(){
        if(SettingsStatus.recommendedFlags) {
            Map<String, Boolean> recFlags = FlagsSharedPref.getAll();
            BOOL_FLAGS.putAll(recFlags);
            Map<String, Long> recLongFlags = FlagsSharedPref.getAllLong();
            LONG_FLAGS.putAll(recLongFlags);
        }
    }

    // Called via addFlags("ultraDataSaverFlags") from the Ultra data saver patch.
    // Every Ultra override lives in handleBoolFlags instead of this map: it is populated once at
    // app init, so anything added here would be frozen until a restart and the auto-metered mode
    // would not apply without one.
    @SuppressWarnings("unused")
    private static void ultraDataSaverFlags() {
    }

    /**
     * Reports that {@code configId} was consulted, if it is one this build is watching.
     *
     * <p>Called on every flag read, so it bails out before touching anything unless debugging
     * is on and the flag is actually interesting.
     */
    private static void watchFlag(String configId) {
        try {
            if (!WATCHED_FLAGS.contains(configId)) return;
            if (!Pref.pikoDebug()) return;
            if (WATCH_HITS.containsKey(configId)) return;
            if (WATCH_HITS.size() >= WATCH_LOG_LIMIT) return;
            WATCH_HITS.put(configId, Boolean.TRUE);
            boolean ours = ULTRA_BG_PREFETCH_KILL_FLAGS.contains(configId);
            Log.d("piko", "[flagWatch] read " + configId + (ours ? "  (Ultra forces this on)" : "")
                    + "  seen so far: " + watchedFlagSummary());
        } catch (Throwable ignored) {
            // A diagnostic must never be able to break a flag read.
        }
    }

    /** Snapshot of every watched flag seen so far, for dumping after reproducing a screen. */
    public static String watchedFlagSummary() {
        try {
            if (!Pref.pikoDebug()) return "";
            StringBuilder sb = new StringBuilder();
            synchronized (WATCH_HITS) {
                for (String id : WATCH_HITS.keySet()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(id);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return "";
        }
    }

    public static void load() {
        addRecommendedFlags();
    }

    public static Boolean handleBoolFlags(long mobileConfigSpecifier) {
        try {
            DeveloperOptionsItem developerOptionsItem = new DeveloperOptionsItem(mobileConfigSpecifier);
            // Sometimes I want to block all the subflags inside a universal ID.
            // In which case I would only add the universal ID in the BOOL_MAP map.
            // If a boolean value is found then it will return else it will check for for the usual config ID
            String universalId = developerOptionsItem.getUniversalId();
            Boolean universalFlag = BOOL_FLAGS.getOrDefault(universalId, null);
            if(universalFlag!=null) return universalFlag;

            // Built from the universalId already resolved above rather than via
            // getConfigId(), which resolves it a second time through reflection on every
            // single flag check.
            String configId = universalId + "::" + developerOptionsItem.getParamId();
            watchFlag(configId);
            // Set membership is tested before the preference is read: this method runs for
            // every flag read in the app, and reading SharedPreferences is not cached.
            if (ULTRA_REELS_KILL_FLAGS.contains(configId) && Pref.ultraBlockReels()) {
                return false;
            }
            // Every flag here is disable_*, so this one takes true rather than false.
            if (ULTRA_BG_PREFETCH_KILL_FLAGS.contains(configId) && Pref.ultraBlockBgPrefetch()) {
                return true;
            }
            return BOOL_FLAGS.getOrDefault(configId, null);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return null;
    }

    // mobileConfig has no dedicated int type, hence the integer-valued flags (counts, limits) are stored and returned.
    // as long instead of boolean
    public static Long handleLongFlags(long mobileConfigSpecifier) {
        try {
            DeveloperOptionsItem developerOptionsItem = new DeveloperOptionsItem(mobileConfigSpecifier);
            String universalId = developerOptionsItem.getUniversalId();
            Long universalFlag = LONG_FLAGS.getOrDefault(universalId, null);
            if(universalFlag!=null) return universalFlag;

            String configId = developerOptionsItem.getConfigId();
            return LONG_FLAGS.getOrDefault(configId, null);
        } catch (Exception e) {
            PikoUtils.logger(e);
        }
        return null;
    }

}
