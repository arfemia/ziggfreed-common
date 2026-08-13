package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * Holds the loaded achievement content and folds it into an {@link AchievementPool} on demand.
 *
 * <p>The layer is rebuilt WHOLESALE from each load event, so a hot re-import is idempotent, and
 * folding is deferred to {@link #resolveAll}: a consumer loads, then asks once and hands the result
 * to its engine.
 *
 * <pre>{@code
 * AchievementPool pool = AchievementAssetStore.getInstance().resolveAll();
 * engine.setAchievements(pool.achievements());
 * }</pre>
 *
 * <p>The store is process-wide because the ASSETS are: one folder, one set of files, however many
 * games read them. Every reader resolves the WHOLE store, so an achievement is written once and
 * whoever runs progression on this server picks it up. Two readers publishing the same id is
 * settled by rank where the content layers meet, and two FILES landing on one id is reported here,
 * naming both, because in that case one of them simply never exists.
 *
 * <p>Assets arrive already whole: the engine's own asset loading resolved their {@code Parent}
 * chains as the files were read.
 */
public final class AchievementAssetStore {

    private static final AchievementAssetStore INSTANCE = new AchievementAssetStore();

    @Nonnull
    public static AchievementAssetStore getInstance() {
        return INSTANCE;
    }

    /** What a fold produced, and everything worth reporting about it. */
    public record Resolution(@Nonnull AchievementPool pool, @Nonnull List<Finding> issues) {

        public Resolution {
            issues = List.copyOf(issues);
        }
    }

    private final Map<String, AchievementAsset> achievements = new ConcurrentHashMap<>();
    /** What the last {@link #merge} noticed about the layer itself, replayed by every fold. */
    private final List<Finding> layerFindings = new ArrayList<>();

    private AchievementAssetStore() {
    }

    /**
     * Rebuild the layer from a load event's decoded assets. Idempotent on re-import.
     *
     * <p>Each is filed under its OWN id rather than the key the event carries, because a
     * {@code _}-marked folder folds into that id ({@code asset/NestedAssetId}) and the event key is
     * only ever the bare filename. Two files landing on one id is reported rather than silently
     * letting the later one win, since the loser simply never appears.
     */
    public synchronized void merge(@Nonnull Map<String, AchievementAsset> layer) {
        achievements.clear();
        layerFindings.clear();
        for (Map.Entry<String, AchievementAsset> e : layer.entrySet()) {
            AchievementAsset asset = e.getValue();
            if (e.getKey() == null || asset == null) {
                continue;
            }
            String id = asset.getId() == null || asset.getId().isBlank()
                    ? e.getKey().toLowerCase(Locale.ROOT)
                    : asset.getId().toLowerCase(Locale.ROOT);
            AchievementAsset clash = achievements.put(id, asset);
            if (clash != null) {
                layerFindings.add(Finding.error(AchievementPoolValidator.DOMAIN, "DUPLICATE_ACHIEVEMENT_ID",
                        "two files both resolve to the achievement id '" + id + "' (" + describe(clash)
                                + " and " + describe(asset) + "), so only one of them exists. Rename one, "
                                + "or put them under differently named _-marked folders", id));
            }
        }
    }

    /** Where an asset came from, for a finding that has to tell two files apart. */
    @Nonnull
    private static String describe(@Nonnull AchievementAsset asset) {
        String path = asset.getSourcePath();
        return path == null || path.isBlank() ? "<unknown source>" : path;
    }

    /** Unmodifiable view of the loaded assets, keyed by id. */
    @Nonnull
    public Map<String, AchievementAsset> assets() {
        return Collections.unmodifiableMap(achievements);
    }

    /** The folded pool, findings logged. */
    @Nonnull
    public AchievementPool resolveAll() {
        Resolution resolution = resolve();
        logIssues(resolution.issues());
        return resolution.pool();
    }

    /**
     * Fold every loaded achievement into one pool, skipping the skeletons that exist only to be
     * inherited from.
     *
     * <p>A skeleton is skipped because it is not a thing anybody can earn: a shared base, or a
     * STENCIL whose id is a pattern a consumer stamps out against its own runtime roster. A
     * consumer that knows how to expand one reads it off {@link #assets()} and expands it there;
     * nothing unearnable ever reaches a pool.
     */
    @Nonnull
    public Resolution resolve() {
        List<Finding> issues = new ArrayList<>(layerFindings);
        Map<String, AchievementDefinition> out = new LinkedHashMap<>();

        List<String> ids = new ArrayList<>(achievements.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            AchievementAsset asset = achievements.get(id);
            if (asset == null) {
                continue;
            }
            reportUnknownParent(asset, id, issues);
            if (asset.isAbstract()) {
                continue;
            }
            out.put(id, asset.toDefinition());
        }
        return new Resolution(new AchievementPool(out), issues);
    }

    /**
     * Report a file naming a {@code Parent} nothing in this store carries. It is a WARNING rather
     * than an error because the parent may be shipped by a pack a given server does not install; the
     * effect is that the file inherited nothing, which is worth saying out loud either way.
     */
    private void reportUnknownParent(@Nonnull AchievementAsset asset, @Nonnull String id,
            @Nonnull List<Finding> issues) {
        String parentId = asset.getParentId();
        if (parentId != null && !achievements.containsKey(parentId)) {
            issues.add(Finding.warning(AchievementPoolValidator.DOMAIN, "UNKNOWN_PARENT",
                    "Parent names '" + parentId + "', which no loaded file provides, so this inherited "
                            + "nothing and carries only what it authored itself", id));
        }
    }

    /** Log a fold's findings: an error as a warning line, anything else at info. */
    public static void logIssues(@Nonnull List<Finding> issues) {
        ValidationReport.logAll("[achievement] achievement content", issues, SafeLog::warn, SafeLog::info);
    }
}
