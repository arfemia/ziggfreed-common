package com.ziggfreed.common.asset;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Lets an author group asset files into folders AND have the folder name become part of the id, by
 * marking the folders that should count with a leading underscore.
 *
 * <p>The engine names an asset after its FILE, and nothing else: a file's key is its filename minus
 * the extension, so {@code Zones/Wilds/Trork_Trouble.json} and {@code Zones/Ashlands/Trork_Trouble.json}
 * are one and the same id. That leaves an author choosing between folders that read well and ids
 * that do not collide. Marking a folder {@code _Wilds} buys both:
 *
 * <pre>
 * Quests/MMOSkillTree/Zones/_Wilds/Trork_Trouble.json   -&gt;  wilds_trork_trouble
 * Quests/MMOSkillTree/Zones/Trork_Trouble.json          -&gt;  trork_trouble
 * </pre>
 *
 * <p>Only a folder whose name starts with {@code _} contributes anything; every other folder is
 * organisational and inert, so an existing tree keeps the ids it already has and nothing moves
 * under an author until they opt in. Marked folders STACK in path order, joined with {@code _}:
 * {@code _Wilds/_Act1/Trork_Trouble.json} is {@code wilds_act1_trork_trouble}. The whole result is
 * lower-cased, matching how every id in this library is addressed.
 *
 * <p><b>Where this is wired.</b> Quests and achievements both use it ({@code QuestAsset} and
 * {@code AchievementAsset} fold it in as they decode). Every other asset type keys plainly off its
 * filename; wire another type the same way when it wants the same grouping.
 *
 * <p><b>Pick the folder name as carefully as the filename.</b> Renaming a marked folder renames
 * every id beneath it, and an id is what a player's saved progress is filed under, so a rename
 * starts that content over for anyone mid-way through it.
 */
public final class NestedAssetId {

    /** The leading character that makes a directory contribute its name. */
    public static final char MARKER = '_';

    /** What a contributed directory name and the filename are joined with. */
    public static final String SEPARATOR = "_";

    private NestedAssetId() {
    }

    /** Does {@code segment} name a directory that contributes a prefix? */
    public static boolean isPrefixDirectory(@Nullable String segment) {
        return segment != null && segment.length() > 1 && segment.charAt(0) == MARKER;
    }

    /**
     * The prefixes the ancestor directories of {@code relativePath} contribute, in path order.
     *
     * @param relativePath the asset FILE's path (its last segment is the filename, which never
     *                     contributes); {@code /} and {@code \} both separate
     */
    @Nonnull
    public static List<String> prefixesOf(@Nullable String relativePath) {
        List<String> out = new ArrayList<>();
        for (String segment : ancestorsOf(relativePath)) {
            if (isPrefixDirectory(segment)) {
                out.add(segment.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * The effective id for an asset file: every marked ancestor directory's name, then the
     * filename-derived id, joined with {@code _} and lower-cased.
     *
     * @param relativePath the asset file's path RELATIVE to its type root (see
     *                     {@link #effectiveId(Path, String, String)} to trim an absolute one)
     * @param filenameId   the id the engine derived from the filename
     */
    @Nonnull
    public static String effectiveId(@Nullable String relativePath, @Nonnull String filenameId) {
        String base = filenameId == null ? "" : filenameId.trim().toLowerCase(Locale.ROOT);
        List<String> prefixes = prefixesOf(relativePath);
        if (prefixes.isEmpty()) {
            return base;
        }
        return String.join(SEPARATOR, prefixes) + SEPARATOR + base;
    }

    /**
     * As {@link #effectiveId(String, String)}, from the ABSOLUTE path the engine hands a codec.
     *
     * <p>{@code typeRoot} is the store's content path ({@code "ZiggfreedCommon/Quests"}), and only
     * directories BELOW it are read. That bound is what keeps a developer's own folder layout out of
     * the ids: a repo checked out at {@code D:\_work\packs\...} would otherwise contribute
     * {@code work} to every id in it. When {@code typeRoot} is blank, or the path does not contain
     * it, every ancestor directory is read instead.
     */
    @Nonnull
    public static String effectiveId(@Nullable Path assetPath, @Nullable String typeRoot,
            @Nonnull String filenameId) {
        return effectiveId(relativize(assetPath, typeRoot), filenameId);
    }

    /**
     * The part of {@code assetPath} below {@code typeRoot}, as a {@code /}-joined string, or the
     * whole path when the root is blank or absent. The LAST occurrence of the root wins, so a pack
     * that happens to repeat a folder name higher up cannot shadow the real one.
     */
    @Nullable
    public static String relativize(@Nullable Path assetPath, @Nullable String typeRoot) {
        if (assetPath == null) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (Path part : assetPath) {
            segments.add(part.toString());
        }
        List<String> root = splitPath(typeRoot);
        int start = root.isEmpty() ? 0 : indexAfterLast(segments, root);
        if (start < 0) {
            start = 0;
        }
        return String.join("/", segments.subList(start, segments.size()));
    }

    // ==================== internals ====================

    /** Every segment of {@code relativePath} except the last (the filename). */
    @Nonnull
    private static List<String> ancestorsOf(@Nullable String relativePath) {
        List<String> segments = splitPath(relativePath);
        if (segments.size() <= 1) {
            return List.of();
        }
        return segments.subList(0, segments.size() - 1);
    }

    @Nonnull
    private static List<String> splitPath(@Nullable String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        for (String segment : path.split("[/\\\\]")) {
            if (!segment.isBlank()) {
                out.add(segment.trim());
            }
        }
        return out;
    }

    /**
     * The index just past the LAST run of {@code needle} inside {@code haystack}, or -1 when the run
     * does not occur. Comparison ignores case, because a content path is authored by hand.
     */
    private static int indexAfterLast(@Nonnull List<String> haystack, @Nonnull List<String> needle) {
        for (int start = haystack.size() - needle.size(); start >= 0; start--) {
            boolean match = true;
            for (int i = 0; i < needle.size(); i++) {
                if (!haystack.get(start + i).equalsIgnoreCase(needle.get(i))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return start + needle.size();
            }
        }
        return -1;
    }
}
