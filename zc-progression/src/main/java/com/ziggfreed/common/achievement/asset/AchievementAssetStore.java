package com.ziggfreed.common.achievement.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.ModGateFold;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.progress.asset.ContentRewardsAsset;
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

    /** The store's mod-gate label, which its drop lines carry (a contract the season boot pair parses). */
    public static final String MOD_GATE_STORE = "Achievements";

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
    /**
     * Achievement ids the mod gate refused at load (zc-core {@code ModGates}, through the registrar),
     * lower-cased, each to the missing mod that refused it: a reference to one is absent by design
     * ({@link #modGateRefused}).
     */
    @Nonnull
    private volatile Map<String, String> gatedOut = Map.of();

    private AchievementAssetStore() {
    }

    /**
     * Rebuild the layer from the load handler's gated fold ({@code AssetMergeAdapter.gate}): its layer, and
     * each refused id with the mod that refused it, so a reference to one reads as refused, never as
     * unknown ({@link #modGateRefused}). Idempotent on re-import.
     */
    public synchronized void merge(@Nonnull ModGateFold<AchievementAsset> fold) {
        merge(fold.layer());
        Map<String, String> refused = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : fold.refused().entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank()) {
                refused.put(e.getKey().trim().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        gatedOut = Collections.unmodifiableMap(refused);
    }

    /**
     * Did the mod gate keep the achievement {@code achievementId} off this server on purpose (its file gates
     * on a mod this server does not run)? A yearly copy's id ({@code <base>_<year>}) answers for its base.
     * Matched trimmed and case-folded, as achievement ids are.
     *
     * <p>A validator reads such an id as absent by design: a reference to it (a capstone's
     * {@code MetaChildren}, an earned-achievement requirement) reports nothing, while an id nobody
     * authored still reports as unknown. Ask it for an id the pool lacks.
     */
    public boolean modGateRefused(@Nullable String achievementId) {
        if (achievementId == null || achievementId.isBlank()) {
            return false;
        }
        String id = achievementId.trim().toLowerCase(Locale.ROOT);
        if (gatedOut.containsKey(id)) {
            return true;
        }
        int cut = id.lastIndexOf('_');
        return cut > 0 && isYear(id.substring(cut + 1)) && gatedOut.containsKey(id.substring(0, cut));
    }

    /** Is {@code text} a four-digit year, the tail a yearly copy's id carries? */
    private static boolean isYear(@Nonnull String text) {
        if (text.length() != 4) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rebuild the layer from a load event's decoded assets, refusing nothing. Idempotent on re-import.
     *
     * <p>Each is filed under its OWN id rather than the key the event carries, because a
     * {@code _}-marked folder folds into that id ({@code asset/NestedAssetId}) and the event key is
     * only ever the bare filename. Two files landing on one id is reported rather than silently
     * letting the later one win, since the loser simply never appears.
     */
    public synchronized void merge(@Nonnull Map<String, AchievementAsset> layer) {
        achievements.clear();
        layerFindings.clear();
        gatedOut = Map.of();
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
     * inherited from. A file naming an {@code Occurrence} folds as its yearly copies, read through
     * zc-core's occurrence slot ({@code OccurrenceReader}).
     *
     * <p>A skeleton is skipped because it is not a thing anybody can earn: a shared base, or a
     * STENCIL whose id is a pattern a consumer stamps out against its own runtime roster. A
     * consumer that knows how to expand one reads it off {@link #assets()} and expands it there;
     * nothing unearnable ever reaches a pool.
     *
     * <p>A reward row whose own {@code Requires} names a missing mod is absent from what the pool pays
     * ({@code RewardEntryAsset.toSpec}); this fold counts those rows once per file that folds, whatever its
     * yearly copies (a skeleton's only through the files that inherit them), in the store's one row line
     * per missing mod.
     */
    @Nonnull
    public Resolution resolve() {
        Resolution resolution = resolve(achievements, layerFindings, OccurrenceReader.LIVE, this::modGateRefused);
        ModGates.reportRewardRows(MOD_GATE_STORE, gatedRows(achievements));
        return resolution;
    }

    /** One entry per reward row the mod gate leaves out of the files that fold, naming its missing mod. */
    @Nonnull
    private static List<String> gatedRows(@Nonnull Map<String, AchievementAsset> assets) {
        List<String> out = new ArrayList<>();
        for (AchievementAsset asset : assets.values()) {
            if (asset != null && !asset.isAbstract()) {
                ContentRewardsAsset.collectMissingMods(asset.getRewards(), out);
            }
        }
        return out;
    }

    /**
     * Fold {@code assets} into one pool: every ordinary file as itself, every file naming an
     * {@code Occurrence} as one copy per year ({@link OccurrenceMinting}), and no skeleton at all.
     * Pure over its arguments, so the whole fold is pinned with no store and no calendar behind it.
     */
    @Nonnull
    static Resolution resolve(@Nonnull Map<String, AchievementAsset> assets,
            @Nonnull List<Finding> layerFindings, @Nonnull OccurrenceReader calendar) {
        return resolve(assets, layerFindings, calendar, id -> false);
    }

    /**
     * {@link #resolve(Map, List, OccurrenceReader)}, with {@code gateRefused} naming the ids the mod gate kept
     * out: a {@code Parent} naming one is absent by design, so no finding names it.
     */
    @Nonnull
    static Resolution resolve(@Nonnull Map<String, AchievementAsset> assets,
            @Nonnull List<Finding> layerFindings, @Nonnull OccurrenceReader calendar,
            @Nonnull Predicate<String> gateRefused) {
        List<Finding> issues = new ArrayList<>(layerFindings);
        Map<String, AchievementDefinition> out = new LinkedHashMap<>();

        Map<String, AchievementAsset.MetaSelector> selectors = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>(assets.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            AchievementAsset asset = assets.get(id);
            if (asset == null) {
                continue;
            }
            reportUnknownParent(asset, id, assets, gateRefused, issues);
            if (asset.isAbstract()) {
                continue;
            }
            AchievementAsset.MetaSelector selector = asset.getMetaSelector();
            AchievementAsset.Occurrence occurrence = asset.getOccurrence();
            if (occurrence == null) {
                out.put(id, asset.toDefinition());
                if (selector != null) {
                    selectors.put(id, selector);
                }
                continue;
            }
            for (AchievementDefinition copy
                    : OccurrenceMinting.mintAll(asset, id, occurrence, assets, calendar, issues)) {
                out.put(copy.id(), copy);
                if (selector != null) {
                    selectors.put(copy.id(), selector);
                }
            }
        }
        MetaSelection.apply(out, selectors, calendar, issues);
        return new Resolution(new AchievementPool(out), issues);
    }

    /**
     * Report a file naming a {@code Parent} nothing in the folded layer carries. It is a WARNING rather
     * than an error because the parent may be shipped by a pack a given server does not install; the
     * effect is that the file inherited nothing, which is worth saying out loud either way. A parent the
     * mod gate refused is absent by design and named nowhere.
     */
    private static void reportUnknownParent(@Nonnull AchievementAsset asset, @Nonnull String id,
            @Nonnull Map<String, AchievementAsset> assets, @Nonnull Predicate<String> gateRefused,
            @Nonnull List<Finding> issues) {
        String parentId = asset.getParentId();
        if (parentId != null && !assets.containsKey(parentId) && !gateRefused.test(parentId)) {
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
