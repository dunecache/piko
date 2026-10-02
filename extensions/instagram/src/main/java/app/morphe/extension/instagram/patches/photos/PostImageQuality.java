/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.photos;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.instagram.common.session.UserSession;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.entity.ImageData;
import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.utils.Pref;

/**
 * Per-post image quality override for the feed overflow menu.
 *
 * <p>Called at the end of {@code ImageUrl.getUrl()} by the Ultra data saver patch. The only
 * operation performed is swapping the resolution token inside a URL string, so a failure
 * anywhere returns the input untouched and the image simply loads at whatever the app asked
 * for. Nothing here can stop a photo from loading.
 *
 * <p>Overrides are keyed by an <em>identity</em> derived from the URL rather than by the URL
 * itself, because Instagram publishes one URL per resolution of the same image and those URLs
 * differ in two ways that make a literal key useless: the resolution token ({@code 1080x1080}
 * vs {@code 320x320}) and an expiring query token. The identity drops the query string and
 * collapses the resolution token, so every variant of one image maps to a single entry that
 * survives both a token refresh and the global Ultra clamp having already shrunk the URL.
 *
 * <p>All app classes are touched reflectively; nothing is imported from the patched app except
 * the stubs the repo already depends on.
 */
@SuppressWarnings("unused")
public final class PostImageQuality {

    /** Widest edge, in pixels, for each tier. {@link #ORIGINAL} means "leave it alone". */
    public static final int ULTRA = 320;
    public static final int LOW = 480;
    public static final int MEDIUM = 720;
    /** Sentinel: no fixed width, use the largest resolution the post published. */
    public static final int ORIGINAL = -1;

    /**
     * Resolution token as it appears in an Instagram CDN path, e.g. {@code .../1080x1080_ab_n.jpg}.
     * IG writes either dimension first depending on the crop, so both are captured and the
     * aspect ratio is preserved on rewrite rather than assuming width-first.
     */
    private static final Pattern SIZE_TOKEN = Pattern.compile("(?<![0-9])([0-9]{2,5})x([0-9]{2,5})(?![0-9])");

    /** Bounded so a long session cannot grow the map without limit. */
    private static final int MAX_ENTRIES = 500;

    private static final String TAG = "piko";

    /** Identity key -> chosen tier. */
    private static final Map<String, Integer> TIERS =
            Collections.synchronizedMap(new LinkedHashMap<String, Integer>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /** Identity key -> widest resolution (px) that the post published, for ORIGINAL. */
    private static final Map<String, Integer> MAX_WIDTHS =
            Collections.synchronizedMap(new LinkedHashMap<String, Integer>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /**
     * Keys already reported, so a RecyclerView rebinding the same photo does not repeat the
     * same line. Bounded, access-ordered, for the same reason the override maps are.
     */
    private static final Set<String> REPORTED =
            Collections.synchronizedSet(new LinkedHashSet<String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 64;
                }
            });

    /**
     * How many distinct URLs are sampled before the sampler gives up. Enough to cover the
     * shapes Instagram uses (feed photo, thumbnail, avatar, carousel child) without flooding
     * logcat on a long session.
     */
    private static final int URL_SAMPLE_LIMIT = 12;

    private static final Set<String> URL_SAMPLES =
            Collections.synchronizedSet(new LinkedHashSet<String>(URL_SAMPLE_LIMIT, 0.75f, true));

    private static void debug(String message) {
        try {
            if (!Pref.pikoDebug()) return;
            Log.d(TAG, "[imgQuality] " + message);
        } catch (Throwable ignored) {
            // Logging must never be the reason a photo fails to load.
        }
    }

    /** Logs {@code message} the first time {@code dedupeKey} is seen. */
    private static void debugOnce(String dedupeKey, String message) {
        try {
            if (!Pref.pikoDebug()) return;
            if (REPORTED.add(dedupeKey)) debug(message);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reports the real shape of the URLs this build sees, including whether the resolution
     * token exists at all. The regex above is a guess about Instagram's CDN path format and
     * this is how that guess gets checked: if a sampled URL has no {@code NxN} token, the
     * rewrite silently never fires and this line is the only evidence of it.
     */
    private static void sampleUrl(String url) {
        try {
            if (!Pref.pikoDebug()) return;
            if (URL_SAMPLES.size() >= URL_SAMPLE_LIMIT) return;
            if (!URL_SAMPLES.add(url)) return;

            String path = url;
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            debug("sample path=" + path
                    + " token=" + (SIZE_TOKEN.matcher(path).find() ? "yes" : "NONE"));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Applies the override for {@code url}, if this image has one.
     *
     * @param url the URL the image loader is about to fetch
     * @return the same URL, or a rewritten one. Never null, never throws.
     */
    public static String rewriteUrl(String url) {
        try {
            if (url == null || url.isEmpty()) return url;
            sampleUrl(url);
            String identity = identityKey(url);
            Integer tier = TIERS.get(identity);
            if (tier == null) return url;

            if (tier == ORIGINAL) {
                // Explicitly requested at full resolution: raise back to whatever the post
                // published, which undoes the global Ultra low-resolution clamp for this post.
                Integer published = MAX_WIDTHS.get(identity);
                if (published == null || published <= 0) {
                    // Nothing better on record. Drop the override so the URL is left untouched.
                    debugOnce("orig-nopub:" + identity,
                            "override ORIGINAL but no published width recorded, dropping it. id=" + identity);
                    forget(identity);
                    return url;
                }
                String grown = resize(url, published, true);
                debugOnce("grow:" + identity + ":" + published,
                        "override ORIGINAL -> " + published + "px, " + unchanged(grown, url)
                                + " id=" + identity);
                return grown;
            }

            String shrunk = resize(url, tier, false);
            debugOnce("shrink:" + identity + ":" + tier,
                    "override " + tier + "px, " + unchanged(shrunk, url) + " id=" + identity);
            return shrunk;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return url;
        }
    }

    /**
     * Rewrites the resolution token to {@code targetWidth}, preserving aspect ratio.
     *
     * @param grow when false the result is {@code min(current, target)}; when true it is
     *             {@code max(current, target)}. Making the operation idempotent matters because
     *             a recycled view can rebind the same wrapper repeatedly, and an idempotent
     *             rewrite cannot ratchet a size up or down on every pass.
     */
    private static String resize(String url, int targetWidth, boolean grow) {
        Matcher matcher = SIZE_TOKEN.matcher(url);
        if (!matcher.find()) {
            // No resolution token to rewrite. Silently ignored rather than mangling the URL.
            debugOnce("notoken:" + url,
                    "NO SIZE TOKEN, override cannot apply. url=" + url);
            return url;
        }

        int first = safeParse(matcher.group(1));
        int second = safeParse(matcher.group(2));
        if (first <= 0 || second <= 0) return url;

        // The token is width-first for landscape media and height-first for portrait; either
        // way the smaller field is the one a cap should apply to, and IG serves either
        // orientation from the same token.
        int currentMin = Math.min(first, second);
        int target = grow ? Math.max(currentMin, targetWidth) : Math.min(currentMin, targetWidth);
        if (target == currentMin) return url;

        int scale = target;
        int other = Math.max(1, Math.round((float) Math.max(first, second) * target / (float) currentMin));
        String replacement = first <= second ? scale + "x" + other : other + "x" + scale;

        debugOnce("resize:" + matcher.group() + "->" + replacement,
                "token " + matcher.group() + " -> " + replacement);

        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    /** Names the reason a rewrite was a no-op, so the log distinguishes "already right" from "failed". */
    private static String unchanged(String result, String original) {
        if (!result.equals(original)) return "rewrote url";
        return "url already at or below target, left alone";
    }

    private static int safeParse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Query string dropped, resolution token collapsed. Two URLs describing the same image at
     * different resolutions therefore produce the same key.
     */
    private static String identityKey(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return SIZE_TOKEN.matcher(path).replaceAll("{S}");
    }

    private static void forget(String identityKey) {
        TIERS.remove(identityKey);
        MAX_WIDTHS.remove(identityKey);
    }

    /**
     * Records the tier for every image variant of {@code mediaData} and remembers the widest
     * resolution each variant list published, which is what ORIGINAL resolves to.
     *
     * @param currentMediaIndex carousel position; ignored for single-image posts
     */
    public static void setForPost(Object mediaObject, UserSession userSession, int currentMediaIndex, int tier) {
        try {
            MediaData mediaData = new MediaData(mediaObject, userSession);
            // getImageVariants() is a raw List, hence the cast per element.
            List<?> variants = mediaData.getImageVariants();
            if (variants == null || variants.isEmpty()) {
                PikoUtils.toast(str("piko_post_quality_no_image"));
                return;
            }

            // Every variant of one image collapses to the same identity key, so this runs
            // once per post rather than once per variant.
            String identityKey = null;
            int widest = 0;
            for (Object element : variants) {
                if (!(element instanceof ImageData)) continue;
                ImageData variant = (ImageData) element;

                String url = variant.getUrl();
                if (url == null) continue;

                if (identityKey == null) identityKey = identityKey(url);

                Integer variantWidth = variant.getWidth();
                if (variantWidth != null && variantWidth > widest) widest = variantWidth;
            }

            if (identityKey == null) {
                PikoUtils.toast(str("piko_post_quality_no_image"));
                return;
            }

            // ORIGINAL is kept as a stored tier rather than deleted: rewriteUrl needs the
            // entry present in order to look up the published width and undo the global
            // Ultra clamp for this post. Deleting it here would make the choice a no-op.
            TIERS.put(identityKey, tier);
            MAX_WIDTHS.put(identityKey, widest);

            debug("registered tier=" + tier + " widest=" + widest
                    + " variants=" + variants.size() + " id=" + identityKey);

            PikoUtils.toast(str("piko_post_quality_set") + " " + tierLabel(tier));
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    public static void showQualityDialog(Context context, Object mediaObject, UserSession userSession, int currentMediaIndex) {
        try {
            final List<Integer> tiers = new ArrayList<>();
            tiers.add(ULTRA);
            tiers.add(LOW);
            tiers.add(MEDIUM);
            tiers.add(ORIGINAL);

            List<String> labels = new ArrayList<>();
            for (int tier : tiers) labels.add(tierLabel(tier));
            CharSequence[] items = labels.toArray(new CharSequence[0]);

            InstagramDialogBox dialog = new InstagramDialogBox(context);
            dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    if (which < 0 || which >= tiers.size()) return;
                    setForPost(mediaObject, userSession, currentMediaIndex, tiers.get(which));
                }
            });
            dialog.setTitle(str("piko_post_quality_title"));
            dialog.setNegativeButton(str("piko_close"), (d, which) -> d.dismiss());
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);
            Dialog dlg = dialog.getDialog();
            dlg.show();
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    public static String tierLabel(int tier) {
        if (tier == ORIGINAL) return str("piko_post_quality_original");
        if (tier == MEDIUM) return str("piko_post_quality_medium");
        if (tier == LOW) return str("piko_post_quality_low");
        return str("piko_post_quality_ultra");
    }

    private PostImageQuality() {
    }
}