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
 * <p>Every layer is rebuilt WHOLESALE from each load event, so a hot re-import is idempotent, and
 * folding is deferred to {@link #resolveAll}: a consumer registers its value sources first, then
 * asks once and hands the result to its engine.
 *
 * <pre>{@code
 * QuestPool pool = QuestAssetStore.getInstance().resolveAll(myEnumerators);
 * engine.setQuests(pool.quests());
 * }</pre>
 *
 * <p>The store is process-wide because the ASSETS are: one folder, one set of files, however many
 * games read them. Every reader resolves the WHOLE store, so a quest is written once and whoever
 * runs progression on this server picks it up. Two FILES landing on one id is reported here, naming
 * both, because in that case one of them simply never exists.
 *
 * <p>Authored quests are already whole when they arrive: the engine's own asset loading resolved
 * their {@code Parent} chains as the files were read. Only GENERATED bodies and the owner's own
 * files are decoded here, and they go through the identical codec against the identical parent, so
 * nothing about either is a special case.
 *
 * <h2>The three layers, lowest to highest</h2>
 *
 * <ol>
 *   <li><b>The files the engine loaded</b> ({@code Server/ZiggfreedCommon/Quests/**}, one quest
 *       per file across every pack, {@link #mergeQuests}).</li>
 *   <li><b>The contributed layer</b> ({@link #mergeContributed}): whole, already-decoded quest
 *       bodies a consumer read from some other source - an older authoring format it converts at
 *       its own load event - handed in as ONE layer and replaced whole by the next hand-in.</li>
 *   <li><b>The owner folder</b> ({@code mods/ziggfreedcommon/quests/<Id>.json},
 *       {@link QuestOwnerLayers}): the server owner's last word, one quest per file, read afresh
 *       at every fold.</li>
 * </ol>
 *
 * <p>A higher layer wins per id SILENTLY - an owner's file over a converted one over a pack's is
 * exactly what a layer is for - while an id twice INSIDE one layer is reported by name, because
 * nothing can say which of the two the author meant. Generators expand against the composed view,
 * so an authored id in any layer beats a generated one and a base in any layer is inherited from.
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
    /** What the last {@link #mergeQuests} noticed about the loaded layer itself, replayed by every fold. */
    private final List<Finding> layerFindings = new ArrayList<>();
    /** The one contributed layer, keyed by id, as the last {@link #mergeContributed} left it. */
    private final Map<String, QuestAsset> contributed = new LinkedHashMap<>();
    /** What the last {@link #mergeContributed} noticed about that layer, replayed by every fold. */
    private final List<Finding> contributedFindings = new ArrayList<>();

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
        file(layer, quests, layerFindings, "files");
    }

    /**
     * Replace the contributed layer wholesale with {@code layer}. Idempotent per load event:
     * handing in the same layer twice is one layer, handing in an empty map takes the layer away.
     * Each entry is filed under its own id exactly as a loaded file is, and an id appearing twice
     * inside the layer is reported as {@code DUPLICATE_QUEST_ID}.
     *
     * <p>This is the seam for a consumer that decodes quests from a format the engine does not load
     * - an older authoring layer it converts at its own {@code LoadedAssetsEvent} - and it is
     * deliberately ONE untracked layer: it folds over the loaded files and under the owner folder,
     * and nothing about who handed it in is kept, because nothing needs to know.
     *
     * <p>The fold happens at the next {@link #resolve}; nothing is published from here. Ask the
     * runtime's own publish to run again afterwards so the layer reaches the engine.
     */
    public synchronized void mergeContributed(@Nonnull Map<String, QuestAsset> layer) {
        contributed.clear();
        contributedFindings.clear();
        file(layer, contributed, contributedFindings, "contributed entries");
    }

    /**
     * File one layer's assets under their own ids, reporting a duplicate inside the layer.
     *
     * @param what what two colliding entries are CALLED in the finding ("files", "contributed
     *             entries"), so a reader knows which layer to look in
     */
    private static void file(@Nonnull Map<String, QuestAsset> layer, @Nonnull Map<String, QuestAsset> into,
            @Nonnull List<Finding> findings, @Nonnull String what) {
        for (Map.Entry<String, QuestAsset> e : layer.entrySet()) {
            QuestAsset asset = e.getValue();
            if (e.getKey() == null || asset == null) {
                continue;
            }
            String id = asset.getId() == null || asset.getId().isBlank()
                    ? e.getKey().toLowerCase(Locale.ROOT)
                    : asset.getId().toLowerCase(Locale.ROOT);
            QuestAsset clash = into.put(id, asset);
            if (clash != null) {
                findings.add(duplicate(what, id, clash, asset));
            }
        }
    }

    /** The finding for two entries of one layer folding to one id; the owner folder reads it too. */
    @Nonnull
    static Finding duplicate(@Nonnull String what, @Nonnull String id, @Nonnull QuestAsset first,
            @Nonnull QuestAsset second) {
        return Finding.error(QuestPoolValidator.DOMAIN, "DUPLICATE_QUEST_ID",
                "two " + what + " both resolve to the quest id '" + id + "' (" + describe(first) + " and "
                        + describe(second) + "), so only one of them exists. Rename one, or put them under "
                        + "differently named _-marked folders", id);
    }

    /** Where an asset came from, for a finding that has to tell two files apart. */
    @Nonnull
    static String describe(@Nonnull QuestAsset asset) {
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

    // ==================== the composed view ====================

    /**
     * The composed view, as a fresh map keyed by id: the loaded files, the contributed layer over
     * them, and the owner folder over both, read from disk right now. Every finding the layers
     * carry and every finding the folder read produced goes onto {@code issues}. Generators expand
     * against exactly this, so what they inherit from and what beats them is what a reader sees.
     */
    @Nonnull
    private synchronized Map<String, QuestAsset> compose(@Nonnull List<Finding> issues) {
        issues.addAll(layerFindings);
        issues.addAll(contributedFindings);
        Map<String, QuestAsset> below = new LinkedHashMap<>(quests);
        below.putAll(contributed);
        Map<String, QuestAsset> out = new LinkedHashMap<>(below);
        out.putAll(QuestOwnerLayers.read(below, issues));
        return out;
    }

    // ==================== reads ====================

    /**
     * Unmodifiable view of the layer the engine loaded, keyed by id. Neither the contributed layer
     * nor the owner folder is in it; {@link #composedAssets()} is the view a fold reads.
     */
    @Nonnull
    public Map<String, QuestAsset> assets() {
        return Collections.unmodifiableMap(quests);
    }

    /**
     * The loaded files with the contributed layer and then the owner folder laid over them, as a
     * fresh copy. The folder is read afresh for it, exactly as a fold reads it.
     */
    @Nonnull
    public Map<String, QuestAsset> composedAssets() {
        return compose(new ArrayList<>());
    }

    /** Unmodifiable view of the loaded generators, keyed by id. */
    @Nonnull
    public Map<String, QuestGeneratorAsset> generators() {
        return Collections.unmodifiableMap(generators);
    }

    // ==================== the fold ====================

    /** The folded pool, findings logged. */
    @Nonnull
    public QuestPool resolveAll(@Nullable QuestEnumeratorRegistry enumerators) {
        Resolution resolution = resolve(enumerators);
        logIssues(resolution.issues());
        return resolution.pool();
    }

    /**
     * Fold every authored quest - loaded, contributed or the owner's - plus everything the
     * generators write into one pool. The owner folder is re-read by every call, so whatever
     * re-publishes the store re-reads it: the boot publish, a reload, a hot re-import.
     *
     * <p>An authored file always beats a generated quest of the same id, so a family member that
     * needs to be special is made special by writing the file - and the collision is reported, since
     * the other possibility is that a generator's {@code IdPattern} is too coarse.
     *
     * @param enumerators the registered value sources a generator axis may name
     */
    @Nonnull
    public Resolution resolve(@Nullable QuestEnumeratorRegistry enumerators) {
        List<Finding> issues = new ArrayList<>();
        Map<String, QuestAsset> authored = compose(issues);
        Map<String, QuestDefinition> out = new LinkedHashMap<>();

        List<String> authoredIds = new ArrayList<>(authored.keySet());
        Collections.sort(authoredIds);
        for (String id : authoredIds) {
            QuestAsset asset = authored.get(id);
            if (asset == null || asset.isAbstract()) {
                continue;
            }
            out.put(id, asset.toDefinition(null));
            // Reported at the fold because only the ASSET still carries what the author typed;
            // the folded rule has already fallen back to a default for anything unparseable.
            issues.addAll(QuestPoolValidator.repeatFindings(asset.getRepeat(), id));
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
                QuestAsset base = authored.get(body.baseId());
                if (base == null) {
                    issues.add(Finding.error(QuestPoolValidator.DOMAIN, "UNKNOWN_BASE",
                            "Base '" + body.baseId() + "' is not a quest anybody authored, so '" + body.id()
                                    + "' inherits nothing and is skipped", generatorId));
                    continue;
                }
                if (authored.containsKey(body.id())) {
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
                out.put(body.id(), decoded.toDefinition(generatorId));
                issues.addAll(QuestPoolValidator.repeatFindings(decoded.getRepeat(), body.id()));
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
