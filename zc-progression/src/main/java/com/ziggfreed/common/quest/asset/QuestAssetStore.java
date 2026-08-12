package com.ziggfreed.common.quest.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.quest.asset.QuestGeneratorExpander.Expansion;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * Holds the loaded quest content - the authored {@link QuestAsset}s and the
 * {@link QuestGeneratorAsset}s - and folds them into a {@link QuestPool} on demand.
 *
 * <p>Both layers are rebuilt WHOLESALE from each load event, so a hot re-import is idempotent, and
 * folding is deferred to {@link #resolveAll}: a consumer registers its value sources first, then
 * asks once and hands the result to its engine.
 *
 * <pre>{@code
 * QuestPool pool = QuestAssetStore.getInstance().resolveAll("yourmod", myEnumerators);
 * engine.setQuests(pool.quests());
 * }</pre>
 *
 * <p>The store is process-wide because the ASSETS are: one folder, one set of files, however many
 * games read them. Each consumer filters by {@code Owner}, and a quest that names no owner is
 * picked up by every reader (which is what an unowned shared base wants).
 *
 * <p>Authored quests are already whole when they arrive: the engine's own asset loading resolved
 * their {@code Parent} chains as the files were read. Only GENERATED bodies are decoded here, and
 * they go through the identical codec against the identical parent, so nothing about a generated
 * quest is a special case.
 */
public final class QuestAssetStore {

    private static final QuestAssetStore INSTANCE = new QuestAssetStore();

    @Nonnull
    public static QuestAssetStore getInstance() {
        return INSTANCE;
    }

    /** What a fold produced, and everything worth reporting about it. */
    public record Resolution(@Nonnull QuestPool pool, @Nonnull List<Finding> issues) {

        public Resolution {
            issues = List.copyOf(issues);
        }
    }

    private final Map<String, QuestAsset> quests = new ConcurrentHashMap<>();
    private final Map<String, QuestGeneratorAsset> generators = new ConcurrentHashMap<>();
    /** What the last {@link #mergeQuests} noticed about the layer itself, replayed by every fold. */
    private final List<Finding> layerFindings = new ArrayList<>();

    private QuestAssetStore() {
    }

    /**
     * Rebuild the quest layer from a load event's decoded assets. Idempotent on re-import.
     *
     * <p>Each quest is filed under its OWN id rather than the key the event carries, because a
     * {@code _}-marked folder folds into that id ({@code asset/NestedAssetId}) and the event key is
     * only ever the bare filename. Two files landing on one id is reported rather than silently
     * letting the later one win, since the loser simply never appears.
     */
    public synchronized void mergeQuests(@Nonnull Map<String, QuestAsset> layer) {
        quests.clear();
        layerFindings.clear();
        for (Map.Entry<String, QuestAsset> e : layer.entrySet()) {
            QuestAsset asset = e.getValue();
            if (e.getKey() == null || asset == null) {
                continue;
            }
            String id = asset.getId() == null || asset.getId().isBlank()
                    ? e.getKey().toLowerCase(Locale.ROOT)
                    : asset.getId().toLowerCase(Locale.ROOT);
            QuestAsset clash = quests.put(id, asset);
            if (clash != null) {
                layerFindings.add(Finding.error(QuestPoolValidator.DOMAIN, "DUPLICATE_QUEST_ID",
                        "two files both resolve to the quest id '" + id + "' (" + describe(clash)
                                + " and " + describe(asset) + "), so only one of them exists. Rename one, "
                                + "or put them under differently named _-marked folders", id));
            }
        }
    }

    /** Where an asset came from, for a finding that has to tell two files apart. */
    @Nonnull
    private static String describe(@Nonnull QuestAsset asset) {
        String path = asset.getSourcePath();
        return path == null || path.isBlank() ? "<generated>" : path;
    }

    /** Rebuild the generator layer from a load event's decoded assets. Idempotent on re-import. */
    public synchronized void mergeGenerators(@Nonnull Map<String, QuestGeneratorAsset> layer) {
        generators.clear();
        for (Map.Entry<String, QuestGeneratorAsset> e : layer.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                generators.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
    }

    /** Unmodifiable view of the loaded quest assets, keyed by id. */
    @Nonnull
    public Map<String, QuestAsset> assets() {
        return Collections.unmodifiableMap(quests);
    }

    /** Unmodifiable view of the loaded generators, keyed by id. */
    @Nonnull
    public Map<String, QuestGeneratorAsset> generators() {
        return Collections.unmodifiableMap(generators);
    }

    /** The folded pool for {@code ownerFilter}, findings logged. */
    @Nonnull
    public QuestPool resolveAll(@Nullable String ownerFilter, @Nullable QuestEnumeratorRegistry enumerators) {
        Resolution resolution = resolve(ownerFilter, enumerators);
        logIssues(resolution.issues());
        return resolution.pool();
    }

    /**
     * Fold every authored quest plus everything the generators write into one pool.
     *
     * <p>An authored file always beats a generated quest of the same id, so a family member that
     * needs to be special is made special by writing the file - and the collision is reported, since
     * the other possibility is that a generator's {@code IdPattern} is too coarse.
     *
     * @param ownerFilter only emit quests with this owner (null = every quest, whoever owns it)
     * @param enumerators the registered value sources a generator axis may name
     */
    @Nonnull
    public Resolution resolve(@Nullable String ownerFilter, @Nullable QuestEnumeratorRegistry enumerators) {
        String filter = ownerFilter == null || ownerFilter.isBlank()
                ? null : ownerFilter.trim().toLowerCase(Locale.ROOT);
        List<Finding> issues = new ArrayList<>(layerFindings);
        Map<String, QuestDefinition> out = new LinkedHashMap<>();

        List<String> authoredIds = new ArrayList<>(quests.keySet());
        Collections.sort(authoredIds);
        for (String id : authoredIds) {
            QuestAsset asset = quests.get(id);
            if (asset == null || asset.isAbstract()) {
                continue;
            }
            QuestDefinition definition = asset.toDefinition(null);
            if (definition.matchesOwner(filter)) {
                out.put(id, definition);
            }
        }

        List<String> generatorIds = new ArrayList<>(generators.keySet());
        Collections.sort(generatorIds);
        for (String generatorId : generatorIds) {
            QuestGeneratorAsset generator = generators.get(generatorId);
            if (generator == null) {
                continue;
            }
            Expansion expansion = QuestGeneratorExpander.expand(generator, enumerators);
            issues.addAll(expansion.issues());
            for (GeneratedQuestBody body : expansion.bodies()) {
                QuestAsset base = quests.get(body.baseId());
                if (base == null) {
                    issues.add(Finding.error(QuestPoolValidator.DOMAIN, "UNKNOWN_BASE",
                            "Base '" + body.baseId() + "' is not a quest anybody authored, so '" + body.id()
                                    + "' inherits nothing and is skipped", generatorId));
                    continue;
                }
                if (quests.containsKey(body.id())) {
                    issues.add(Finding.error(QuestPoolValidator.DOMAIN, "ID_COLLISION",
                            "'" + body.id() + "' is also authored as its own quest file, which wins; either "
                                    + "delete the file or widen IdPattern so the two stop colliding",
                            generatorId));
                    continue;
                }
                if (out.containsKey(body.id())) {
                    issues.add(Finding.error(QuestPoolValidator.DOMAIN, "ID_COLLISION",
                            "two generators both produce '" + body.id() + "', so only the first one exists",
                            generatorId));
                    continue;
                }
                QuestAsset decoded = decodeGenerated(body, base, generatorId, issues);
                if (decoded == null || decoded.isAbstract()) {
                    continue;
                }
                QuestDefinition definition = decoded.toDefinition(generatorId);
                if (definition.matchesOwner(filter)) {
                    out.put(body.id(), definition);
                }
            }
        }
        return new Resolution(new QuestPool(out), issues);
    }

    /**
     * Decode one generated body against its base through the SAME codec path the asset store uses
     * for a hand-written child, so inheritance behaves identically. A body that fails to decode is
     * reported and skipped rather than taking the whole fold down.
     */
    @Nullable
    private static QuestAsset decodeGenerated(@Nonnull GeneratedQuestBody body, @Nonnull QuestAsset base,
            @Nonnull String generatorId, @Nonnull List<Finding> issues) {
        try {
            AssetExtraInfo.Data data = new AssetExtraInfo.Data(QuestAsset.class, body.id(), body.baseId());
            QuestAsset decoded = QuestAsset.CODEC.decodeAndInheritJsonAsset(
                    RawJsonReader.fromJsonString(body.body().toString()), base, new AssetExtraInfo<>(data));
            if (decoded == null) {
                issues.add(Finding.error(QuestPoolValidator.DOMAIN, "DECODE_FAILED",
                        "'" + body.id() + "' produced no quest at all; check the Child body against the quest "
                                + "schema", generatorId));
            }
            return decoded;
        } catch (Exception e) {
            issues.add(Finding.error(QuestPoolValidator.DOMAIN, "DECODE_FAILED",
                    "'" + body.id() + "' could not be read as a quest: " + e.getMessage(), generatorId));
            return null;
        }
    }

    /** Log a fold's findings: an error as a warning line, anything else at info. */
    public static void logIssues(@Nonnull List<Finding> issues) {
        ValidationReport.logAll("[quest] quest content", issues, SafeLog::warn, SafeLog::info);
    }
}
