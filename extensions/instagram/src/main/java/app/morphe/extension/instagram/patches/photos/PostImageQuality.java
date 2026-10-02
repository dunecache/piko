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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.instagram.common.session.UserSession;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.entity.ImageData;
import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.discovery.ViewerMenuDiscovery;
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

    /**
     * Identity key -> every resolution the post published, widest last.
     *
     * <p>This is what a tier resolves against. Ultra clamps the width the viewer *asks* for
     * ({@code Pref.improveImageViewing}), it does not filter {@code image_versions2}, so the
     * candidates reaching the overflow menu are still the untouched originals. Swapping in
     * one of these real URLs is therefore what actually beats the clamp, whereas rewriting a
     * {@code NxN} token only works while that token happens to exist in the path.
     */
    private static final Map<String, List<Variant>> VARIANTS =
            Collections.synchronizedMap(new LinkedHashMap<String, List<Variant>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Variant>> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /** One published resolution: the width Instagram reports and the URL that serves it. */
    private static final class Variant {
        final int width;
        final String url;

        Variant(int width, String url) {
            this.width = width;
            this.url = url;
        }
    }

    /**
     * Keys already reported, so a RecyclerView rebinding the same photo does not repeat the
     * same line. Bounded, access-ordered, for the same reason the override maps are.
     *
     * <p>Used as an ordered set via {@link Map#put}. Not a {@code LinkedHashSet}: the
     * access-order constructor of that class is not available on the Android API level this
     * extension compiles against, and {@code LinkedHashMap}'s is.
     */
    private static final Map<String, Boolean> REPORTED =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
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

    private static final Map<String, Boolean> URL_SAMPLES =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(URL_SAMPLE_LIMIT, 0.75f, true));

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
            if (REPORTED.put(dedupeKey, Boolean.TRUE) == null) debug(message);
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
            if (URL_SAMPLES.put(url, Boolean.TRUE) != null) return;

            String path = url;
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            debug("sample path=" + path
                    + " token=" + (SIZE_TOKEN.matcher(path).find() ? "yes" : "NONE")
                    + ViewerMenuDiscovery.describeCallSite());
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

            // ORIGINAL has no fixed cap, so it resolves against the widest variant the post
            // actually published. Using an unbounded target here would overflow the token
            // arithmetic inside resize(), so the real width is looked up instead.
            boolean grow = tier == ORIGINAL;
            int target = grow ? widestFor(identity) : tier;

            // Preferred path: the incoming URL still carries a size token, so the tier can be
            // applied to the app's own URL. Keeps whatever crop/suffix the app chose.
            if (target > 0) {
                String resized = resize(url, target, grow);
                if (!resized.equals(url)) {
                    debugOnce("tier:" + identity + ":" + tier,
                            "override " + (grow ? "ORIGINAL " + target + "px" : tier + "px")
                                    + ", rewrote url id=" + identity);
                    return resized;
                }
            }

            // No usable token in the path (or already at target): swap in a real published
            // URL instead. Ultra picks a different candidate rather than rewriting the URL,
            // so the incoming string is one of the variants recorded below and the override
            // can still be honoured by swapping it back out.
            String swapped = swapVariant(identity, tier, url);
            if (swapped != null && !swapped.equals(url)) {
                debugOnce("swap:" + identity + ":" + tier,
                        "override " + (grow ? "ORIGINAL" : tier + "px")
                                + ", swapped variant url id=" + identity);
                return swapped;
            }

            debugOnce("noop:" + identity + ":" + tier,
                    "override " + (grow ? "ORIGINAL" : tier + "px")
                            + " but no variant reached it, left alone id=" + identity);
            return url;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return url;
        }
    }

    /**
     * Widest published width on record for {@code identity}, or 0 when nothing is recorded.
     */
    private static int widestFor(String identity) {
        List<Variant> variants = VARIANTS.get(identity);
        if (variants == null) return 0;
        int widest = 0;
        for (Variant variant : variants) widest = Math.max(widest, variant.width);
        return widest;
    }

    /**
     * Picks the published variant closest to {@code tier} and returns it, or null when the
     * identity has no variants on record.
     *
     * <p>{@link #ORIGINAL} means "widest". For a real tier the widest variant at or under the
     * target is used; when even the smallest published variant exceeds the target, the
     * smallest is used instead, because returning nothing would silently leave the override
     * unapplied.
     *
     * <p>The query string of {@code incoming} is kept rather than the stored one: signatures
     * expire, and the URL the loader just handed us is the freshest signature available. The
     * path is what actually encodes the resolution, so swapping it is sufficient. Falls back
     * to the stored full URL when the incoming URL has no query to reuse.
     */
    private static String swapVariant(String identity, int tier, String incoming) {
        List<Variant> variants = VARIANTS.get(identity);
        if (variants == null || variants.isEmpty()) return null;

        boolean widest = tier == ORIGINAL;
        Variant best = null;
        for (Variant variant : variants) {
            if (!widest && variant.width > tier) continue;
            if (best == null || variant.width > best.width) best = variant;
        }
        if (best == null) {
            // Every published variant is larger than the target: use the smallest one so the
            // override still shrinks the image, just as far as Instagram allows.
            for (Variant variant : variants) {
                if (best == null || variant.width < best.width) best = variant;
            }
        }
        if (best == null) return null;

        int query = incoming == null ? -1 : incoming.indexOf('?');
        if (query < 0) return best.url;

        int bestQuery = best.url.indexOf('?');
        String path = bestQuery < 0 ? best.url : best.url.substring(0, bestQuery);
        return path + incoming.substring(query);
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

    /**
     * Records the tier for every image variant of {@code mediaData}, together with the
     * published resolution each one serves, which is what a tier resolves against.
     *
     * <p>The tier is registered under <em>every</em> variant's identity rather than one shared
     * identity. When the URL path carries a {@code NxN} token all variants collapse to a
     * single identity anyway; when it does not, each candidate has its own filename and its
     * own identity, and registering only the first would leave every other candidate -- which
     * includes the one Ultra makes Instagram select -- unmatched.
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

            List<Variant> published = new ArrayList<>();
            List<String> identities = new ArrayList<>();
            for (Object element : variants) {
                if (!(element instanceof ImageData)) continue;
                ImageData variant = (ImageData) element;

                String url = variant.getUrl();
                if (url == null || url.isEmpty()) continue;

                Integer variantWidth = variant.getWidth();
                int width = variantWidth == null || variantWidth <= 0 ? 0 : variantWidth;

                published.add(new Variant(width, url));
                identities.add(identityKey(url));
            }

            if (published.isEmpty()) {
                PikoUtils.toast(str("piko_post_quality_no_image"));
                return;
            }

            int widest = 0;
            for (Variant variant : published) widest = Math.max(widest, variant.width);

            // ORIGINAL is stored as a tier like any other rather than deleted: rewriteUrl needs
            // the entry present in order to look up the published variants and undo the global
            // Ultra clamp for this post.
            for (String identity : identities) {
                TIERS.put(identity, tier);
                VARIANTS.put(identity, published);
            }

            debug("registered tier=" + tier + " widest=" + widest
                    + " variants=" + published.size() + " ids=" + identities.size());

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