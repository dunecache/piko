/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.discovery;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.instagram.utils.Pref;

/**
 * Throwaway diagnostic: reports what activities exist and what menu-like types they hold.
 *
 * <p>The per-post image quality entry currently only appears in the feed's overflow menu,
 * because that is the only menu the repo has a fingerprint for. Adding it to the post viewer's
 * menu means finding the class that builds it, and the mappings files only describe server
 * flags, not view hierarchies — so the class name has to come from the running app.
 *
 * <p>Hooking {@code IgFragmentActivity.onCreate} rather than a viewer-specific class is
 * deliberate: the base activity is guaranteed present, so one hook sees every screen including
 * whichever one opens a post. Reflection then walks each activity's fields looking for types
 * that look like a menu or its options, walking up the hierarchy because the field usually
 * lives on a parent such as the viewer activity.
 *
 * <p>Only ever runs with the debug setting on, and only once per activity class, so the cost is
 * a handful of log lines per screen rather than per view.
 */
@SuppressWarnings("unused")
public final class ViewerMenuDiscovery {

    private static final String TAG = "piko";

    /** How many frames up each hierarchy to look before giving up. */
    private static final int MAX_SUPER_LEVELS = 6;

    /** Keep the log readable when an activity carries many matching fields. */
    private static final int MAX_FIELDS_PER_ACTIVITY = 12;

    /** Activity class name -> true, so each screen is reported once. */
    private static final Map<String, Boolean> SEEN =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(32, 0.75f, true));

    private ViewerMenuDiscovery() {
    }

    public static void onActivityCreated(Activity activity) {
        try {
            if (activity == null) return;
            if (!Pref.pikoDebug()) return;
            if (SEEN.containsKey(activity.getClass().getName())) return;
            SEEN.put(activity.getClass().getName(), Boolean.TRUE);

            List<String> fields = menuLikeFields(activity.getClass());
            Log.d(TAG, "[discovery] activity " + activity.getClass().getName()
                    + "  menu-like fields: " + (fields.isEmpty() ? "none" : fields.toString()));
        } catch (Throwable ignored) {
            // A discovery aid must never be able to affect app start.
        }
    }

    /**
     * Declared fields whose type name mentions a menu or an option, across the class hierarchy.
     *
     * <p>Both name and type are reported because the type is what a fingerprint needs and the
     * name is what tells the two apart when a class has several candidates.
     */
    private static List<String> menuLikeFields(Class<?> start) {
        List<String> out = new ArrayList<>();
        try {
            Class<?> clazz = start;
            for (int level = 0; level < MAX_SUPER_LEVELS && clazz != null; level++) {
                for (Field field : clazz.getDeclaredFields()) {
                    String typeName = field.getType().getName();
                    String lower = typeName.toLowerCase();
                    if (!lower.contains("menu") && !lower.contains("option")) continue;
                    out.add(clazz.getSimpleName() + "." + field.getName() + " : " + typeName);
                    if (out.size() >= MAX_FIELDS_PER_ACTIVITY) return out;
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Describes where an image URL was requested from, for the image quality sampler.
     *
     * <p>Used instead of a second hook: the caller is already on the stack when the URL is
     * fetched, so this answers whether the post viewer asks for images in the same shape as the
     * feed without needing to know the viewer's class in advance.
     */
    public static String describeCallSite() {
        StringBuilder sb = new StringBuilder();
        try {
            Context ctx = app.morphe.extension.crimera.PikoUtils.getContext();
            if (ctx != null) sb.append(" ctx=").append(ctx.getClass().getName());
        } catch (Throwable ignored) {
        }
        try {
            sb.append(" thread=").append(Thread.currentThread().getName());
            int added = 0;
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                if (!frame.getClassName().startsWith("com.instagram.")) continue;
                sb.append("\n    at ").append(frame.getClassName()).append('.').append(frame.getMethodName());
                if (++added >= 5) break;
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
