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
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
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
 * <p>Called at the end of {@code ExtendedImageUrl.getUrl()} by the patch. The only operation
 * performed is swapping the resolution of a URL string, so a failure anywhere returns the input
 * untouched and the image simply loads at whatever the app asked for. Nothing here can stop a
 * photo from loading.
 *
 * <p>Overrides are keyed by an <em>identity</em> derived from the URL rather than by the URL
 * itself, because Instagram publishes one URL per resolution of the same image and those URLs
 * differ in two ways that make a literal key useless: the resolution token ({@code 1080x1080}
 * vs {@code 320x320}) and an expiring query token. The identity drops the query string and
 * collapses the resolution token, so every variant of one image maps to a single entry that
 * survives both a token refresh and a global low-resolution clamp having already shrunk the URL.
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
     * <p>This is what a tier resolves against. A global low-resolution clamp caps the width the
     * viewer *asks* for ({@code Pref.improveImageViewing}), it does not filter
     * {@code image_versions2}, so the candidates reaching the overflow menu are still the
     * untouched originals. Swapping in one of these real URLs is therefore what actually beats
     * the clamp, whereas rewriting a {@code NxN} token only works while that token happens to
     * exist in the path.
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

    /** Unobfuscated on the pinned version, so it can be matched by name without a compile dep. */
    private static final String MEDIA_TYPE = "com.instagram.feed.media.Media";

    /** Class -> its {@code Media} field, or null when it has none. Cached to keep misses cheap. */
    private static final Map<Class<?>, Field> MEDIA_FIELDS = new WeakHashMap<>();

    /** Post id -> last chosen tier, so the sheet can mark the current selection. */
    private static final Map<String, Integer> POST_TIERS =
            Collections.synchronizedMap(new LinkedHashMap<String, Integer>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

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
                    + describeCallSite());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Where the current image URL request comes from: the app context class, the thread, and
     * the top few Instagram frames. Answers whether the viewer asks for images in the same
     * shape as the feed without needing a second hook.
     */
    private static String describeCallSite() {
        StringBuilder sb = new StringBuilder();
        try {
            android.content.Context ctx = PikoUtils.getContext();
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

    /** How far up the hierarchy to look before giving up on finding the Media. */
    private static final int MAX_ANCESTORS = 14;

    /**
     * Opens the quality sheet for whichever post owns {@code anchorView}, resolving the
     * {@code Media} at tap time. Used by the action-bar button, which only ever sees views.
     *
     * <p>Carousels resolve to the currently visible child: the media pager is read at tap
     * time and the child at that index is what gets variants registered and marked.
     */
    public static void openQualitySheet(View anchorView) {
        try {
            if (anchorView == null) return;
            Object media = findMedia(anchorView);
            if (media == null) {
                debug("quality sheet found no Media for " + anchorView.getClass().getName());
                PikoUtils.toast(str("piko_post_quality_no_image"));
                return;
            }
            // Pass the parent Media plus the visible page and let showQualitySheet do the
            // single canonical getMediaAt() resolve. Resolving to the child here as well would
            // key the tier by the child's pk on this surface but by the parent's pk on the
            // overflow-menu surface, so the two entries would never agree on the current tier.
            int page = 0;
            try {
                page = Math.max(0, readCurrentPageIndex(anchorView));
            } catch (Throwable ignored) {
            }
            showQualitySheet(anchorView.getContext(), media, null, page);
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /**
     * The media pager's currently visible page, or 0 when there is none in reach.
     *
     * <p>Walks up a few levels (row/page root, never the activity decor, so the outer tab
     * pager is out of reach) and searches each level's subtree for the nearest photo pager.
     * Single photos have no pager and fall through to 0; feed carousels resolve to the
     * visible child, which is exactly the photo the tier should apply to.
     */
    private static int readCurrentPageIndex(View anchor) {
        try {
            View current = anchor;
            for (int level = 0; level < 8 && current != null; level++) {
                Object pager = findPagerInSubtree(current, 0);
                if (pager != null) {
                    int index = getPagerItem(pager);
                    if (index >= 0) return index;
                }
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** First ViewPager2 (or legacy ViewPager) in the subtree, depth-bounded. */
    private static Object findPagerInSubtree(View root, int depth) {
        try {
            if (root == null || depth > 5) return null;
            String name = root.getClass().getName();
            if (name.startsWith("androidx.viewpager2.widget.ViewPager2")
                    || name.startsWith("androidx.viewpager.widget.ViewPager")) {
                return root;
            }
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    Object found = findPagerInSubtree(group.getChildAt(i), depth + 1);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Reflective {@code getCurrentItem()}, or -1. */
    private static int getPagerItem(Object pager) {
        try {
            Method currentItem = pager.getClass().getMethod("getCurrentItem");
            Object index = currentItem.invoke(pager);
            if (index instanceof Integer) return (Integer) index;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Finds the {@code Media} behind a photo view: first on the view or an ancestor, then via
     * the RecyclerView ViewHolder that actually owns the row.
     *
     * @return the media object, or null if it cannot be identified
     */
    private static Object findMedia(View view) {
        try {
            View current = view;
            for (int level = 0; level < MAX_ANCESTORS && current != null; level++) {
                Object media = readMediaField(current);
                if (media != null) return media;

                Object tag = current.getTag();
                if (tag != null) {
                    media = readMediaField(tag);
                    if (media != null) return media;
                }

                Object holder = readViewHolder(current, view);
                if (holder != null) {
                    media = readMediaField(holder);
                    if (media != null) return media;
                }

                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Resolves the RecyclerView ViewHolder owning {@code target} by walking up to the first
     * RecyclerView ancestor. Entirely reflective so the extension keeps no compile-time
     * dependency on the AndroidX widget. The post viewer is a ViewPager2 and therefore
     * RecyclerView-backed, which is what makes this reach the viewer at all.
     *
     * @return the ViewHolder, or null when there is no RecyclerView above the view
     */
    private static Object readViewHolder(View ancestor, View target) {
        try {
            Class<?> clazz = ancestor.getClass();
            boolean isRecyclerView = false;
            for (Class<?> c = clazz; c != null && !isRecyclerView; c = c.getSuperclass()) {
                isRecyclerView = c.getName().startsWith("androidx.recyclerview.widget.RecyclerView");
            }
            if (!isRecyclerView) return null;

            Method childPosition = clazz.getMethod("getChildAdapterPosition", View.class);
            Object position = childPosition.invoke(ancestor, target);
            if (!(position instanceof Integer) || (Integer) position < 0) return null;

            Method findHolder = clazz.getMethod("findViewHolderForAdapterPosition", int.class);
            return findHolder.invoke(ancestor, position);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Reads a {@code Media}-typed field off {@code owner}, or null. */
    private static Object readMediaField(Object owner) {
        try {
            Field field = mediaFieldFor(owner.getClass());
            if (field == null) return null;
            field.setAccessible(true);
            Object value = field.get(owner);
            return (value != null && value.getClass().getName().equals(MEDIA_TYPE)) ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The {@code Media}-typed field declared by {@code clazz} or one of its superclasses.
     * Resolved once per class; a null result is cached too, so the common miss costs one map
     * lookup instead of a field scan on every tap.
     */
    private static Field mediaFieldFor(Class<?> clazz) {
        try {
            synchronized (MEDIA_FIELDS) {
                if (MEDIA_FIELDS.containsKey(clazz)) return MEDIA_FIELDS.get(clazz);
            }
            Field found = null;
            for (Class<?> c = clazz; c != null && found == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getName().equals(MEDIA_TYPE)) {
                        found = f;
                        break;
                    }
                }
            }
            synchronized (MEDIA_FIELDS) {
                MEDIA_FIELDS.put(clazz, found);
            }
            return found;
        } catch (Throwable ignored) {
            return null;
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
            // URL instead. A global clamp picks a different candidate rather than rewriting the
            // URL, so the incoming string is one of the variants recorded below and the
            // override can still be honoured by swapping it back out.
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
     * includes the one a global clamp makes Instagram select -- unmatched.
     */
    /** Carousel-safe resolve: the child at {@code index}, or the media itself. Never null unless empty. */
    private static MediaData resolveTargetMedia(Object mediaObject, UserSession userSession, int index) {
        try {
            if (mediaObject == null) return null;
            MediaData parent = new MediaData(mediaObject, userSession);
            try {
                MediaData child = parent.getMediaAt(Math.max(0, index));
                if (child != null && child.getObject() != null) return child;
            } catch (Throwable ignored) {
            }
            return parent;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Tier-marking key: parent post id plus carousel index. Falls back to the resolved child's
     * own post id when the parent id is unreadable, so a marking is still distinct per child.
     */
    private static String tierKeyFor(Object mediaObject, UserSession userSession, int index) {
        try {
            int safeIndex = Math.max(0, index);
            String parentId = null;
            try {
                parentId = new MediaData(mediaObject, userSession).getPostID();
            } catch (Throwable ignored) {
            }
            if (parentId != null && !parentId.isEmpty() && !"0".equals(parentId)) {
                return parentId + "#" + safeIndex;
            }
            try {
                MediaData child = resolveTargetMedia(mediaObject, userSession, safeIndex);
                if (child != null) {
                    String childId = child.getPostID();
                    if (childId != null && !childId.isEmpty() && !"0".equals(childId)) {
                        return childId + "#" + safeIndex;
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Previously stored tier for this post+child, checking the legacy plain-postId key too. */
    private static Integer readTierFor(Object mediaObject, UserSession userSession, int index) {
        try {
            String key = tierKeyFor(mediaObject, userSession, index);
            if (key != null) {
                Integer known = POST_TIERS.get(key);
                if (known != null) return known;
                // Entries written before per-child keys existed live under the bare post id.
                int hash = key.indexOf('#');
                if (hash > 0) {
                    Integer legacy = POST_TIERS.get(key.substring(0, hash));
                    if (legacy != null) return legacy;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Records the tier for the carousel child at {@code currentMediaIndex}.
     *
     * @param currentMediaIndex carousel position; ignored for single-image posts
     */
    public static void setForPost(Object mediaObject, UserSession userSession, int currentMediaIndex, int tier) {
        try {
            // Carousels share one Media with N children: resolve the visible child first, the
            // same way downloads do. Registering the parent's variants instead leaves every
            // multi-photo post on "no photos" (or pins the wrong photo's URLs).
            MediaData resolved = resolveTargetMedia(mediaObject, userSession, currentMediaIndex);
            if (resolved == null) {
                PikoUtils.toast(str("piko_post_quality_no_image"));
                return;
            }
            MediaData mediaData = resolved;
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
            // clamp for this post.
            for (String identity : identities) {
                TIERS.put(identity, tier);
                VARIANTS.put(identity, published);
            }

            // Remember the choice per post+child as well, so the sheet can mark the current
            // tier. Keyed by parent post id plus carousel index: carousel children are distinct
            // photos that deserve distinct tiers, and the overflow and action-bar surfaces must
            // agree on the key. A post id read failure must not lose the registration above.
            try {
                String key = tierKeyFor(mediaObject, userSession, currentMediaIndex);
                if (key != null) {
                    POST_TIERS.put(key, tier);
                }
            } catch (Throwable ignored) {
            }

            debug("registered tier=" + tier + " widest=" + widest
                    + " variants=" + published.size() + " ids=" + identities.size());

            PikoUtils.toast(str("piko_post_quality_set") + " " + tierLabel(tier));
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /**
     * Entry point kept for the overflow-menu path. It opens the same bottom sheet as the
     * action-bar button, so both surfaces share one picker UI.
     */
    public static void showQualityDialog(Context context, Object mediaObject, UserSession userSession, int currentMediaIndex) {
        showQualitySheet(context, mediaObject, userSession, currentMediaIndex);
    }

    /**
     * Quality picker, shown through the same IGDS dialog the download variants picker uses.
     * The previous framework-Dialog sheet needed an Activity window token and silently never
     * appeared when the anchor context was a wrapper; IGDS handles the contexts both entry
     * points hand over (feed FragmentActivity, action-bar view context).
     */
    public static void showQualitySheet(final Context context, final Object mediaObject, final UserSession userSession, final int currentMediaIndex) {
        try {
            if (context == null || mediaObject == null) return;
            final int[] tiers = {ULTRA, LOW, MEDIUM, ORIGINAL};

            // Mark this post+child's current tier, if it already has one.
            Integer known = readTierFor(mediaObject, userSession, currentMediaIndex);
            final int currentTier = known == null ? Integer.MIN_VALUE : known;

            final CharSequence[] items = new CharSequence[tiers.length];
            for (int i = 0; i < tiers.length; i++) {
                String label = tierLabel(tiers[i]);
                if (tiers[i] == currentTier) label = "\u2713 " + label;
                items[i] = label;
            }

            InstagramDialogBox dialog;
            try {
                dialog = new InstagramDialogBox(context);
            } catch (Throwable t) {
                PikoUtils.logger(t);
                // IGDS entity placeholders unresolved (patch dependency missing): fall back to
                // the framework sheet rather than showing nothing.
                showQualitySheetFallback(context, mediaObject, userSession, currentMediaIndex, currentTier);
                return;
            }
            try {
                dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            if (which < 0 || which >= tiers.length) return;
                            setForPost(mediaObject, userSession, currentMediaIndex, tiers[which]);
                        } catch (Throwable t) {
                            PikoUtils.logger(t);
                        }
                    }
                });
                dialog.setTitle(str("piko_post_quality_title"));
                dialog.setCancelable(true);
                dialog.setCanceledOnTouchOutside(true);
                Dialog dlg = dialog.getDialog();
                if (dlg == null) throw new IllegalStateException("IGDS dialog is null");
                dlg.show();
            } catch (Throwable t) {
                PikoUtils.logger(t);
                showQualitySheetFallback(context, mediaObject, userSession, currentMediaIndex, currentTier);
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /** Framework-Dialog fallback when the IGDS dialog cannot be built. */
    private static void showQualitySheetFallback(final Context context, final Object mediaObject, final UserSession userSession, final int currentMediaIndex, final int currentTier) {
        try {
            if (context == null) return;
            final int[] tiers = {ULTRA, LOW, MEDIUM, ORIGINAL};

            final Dialog dialog = new Dialog(context);
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            int side = dp(context, 16);
            root.setPadding(side, dp(context, 8), side, dp(context, 16));
            root.setBackground(sheetBackground(context));

            View handle = new View(context);
            LinearLayout.LayoutParams handleParams =
                    new LinearLayout.LayoutParams(dp(context, 40), dp(context, 4));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            handleParams.bottomMargin = dp(context, 10);
            handle.setLayoutParams(handleParams);
            GradientDrawable handleBg = new GradientDrawable();
            handleBg.setShape(GradientDrawable.RECTANGLE);
            handleBg.setCornerRadius(dp(context, 2));
            handleBg.setColor(0xFFBDBDBD);
            handle.setBackground(handleBg);
            root.addView(handle);

            TextView title = new TextView(context);
            title.setText(str("piko_post_quality_title"));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            title.setTextColor(resolveColor(context, android.R.attr.textColorPrimary, Color.BLACK));
            root.addView(title);

            TextView hint = new TextView(context);
            hint.setText(str("piko_post_quality_hint"));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hint.setTextColor(resolveColor(context, android.R.attr.textColorSecondary, Color.GRAY));
            hint.setPadding(0, dp(context, 2), 0, dp(context, 8));
            root.addView(hint);

            final boolean[] dismissed = {false};
            final Runnable dismissSheet = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (dismissed[0]) return;
                        dismissed[0] = true;
                        dialog.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
            };

            for (final int tier : tiers) {
                root.addView(qualityRow(context, tier, tier == currentTier, new Runnable() {
                    @Override
                    public void run() {
                        setForPost(mediaObject, userSession, currentMediaIndex, tier);
                        dismissSheet.run();
                    }
                }));
            }

            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setGravity(Gravity.BOTTOM);
                WindowManager.LayoutParams params = window.getAttributes();
                params.width = WindowManager.LayoutParams.MATCH_PARENT;
                params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                params.dimAmount = 0.5f;
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setAttributes(params);
            }
            dialog.show();

            // Slide up on show. Pure property animation: no animation resources needed.
            root.setTranslationY(dp(context, 320));
            root.animate().translationY(0).setDuration(220).start();
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    /** One tappable tier row: label left, blue check right when it is the current tier. */
    private static View qualityRow(Context context, int tier, boolean selected, final Runnable onPick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 52));
        int ripple = resolveResId(context, android.R.attr.selectableItemBackground);
        if (ripple != 0) row.setBackgroundResource(ripple);
        row.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));

        TextView label = new TextView(context);
        label.setText(tierLabel(tier));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTextColor(resolveColor(context, android.R.attr.textColorPrimary, Color.BLACK));
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelParams);
        row.addView(label);

        TextView check = new TextView(context);
        check.setText("\u2713");
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        check.setTextColor(0xFF0095F6);
        check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        row.addView(check);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    onPick.run();
                } catch (Throwable t) {
                    PikoUtils.logger(t);
                }
            }
        });
        return row;
    }

    /** Top-rounded sheet background in the theme's background color, so dark mode just works. */
    private static GradientDrawable sheetBackground(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        float r = dp(context, 16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(resolveColor(context, android.R.attr.colorBackground, Color.WHITE));
        return bg;
    }

    private static int dp(Context context, int dp) {
        try {
            float density = context.getResources().getDisplayMetrics().density;
            return Math.max(1, Math.round(dp * density));
        } catch (Throwable ignored) {
            return dp;
        }
    }

    private static int resolveColor(Context context, int attr, int fallback) {
        try {
            TypedValue out = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, out, true)) {
                if (out.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && out.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return out.data;
                }
                if (out.resourceId != 0) return context.getColor(out.resourceId);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int resolveResId(Context context, int attr) {
        try {
            TypedValue out = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, out, true)) return out.resourceId;
        } catch (Throwable ignored) {
        }
        return 0;
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
