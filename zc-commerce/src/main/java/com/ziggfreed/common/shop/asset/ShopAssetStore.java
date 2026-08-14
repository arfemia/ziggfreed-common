package com.ziggfreed.common.shop.asset;

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
import com.ziggfreed.common.progress.asset.GeneratedBody;
import com.ziggfreed.common.progress.asset.GeneratorCore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * Holds the loaded offers - the authored {@link ShopEntryAsset}s and the
 * {@link ShopEntryGeneratorAsset}s - and folds them into ONE catalogue on demand.
 *
 * <p>Both layers are rebuilt WHOLESALE from each load event, so a hot re-import is idempotent, and
 * folding is deferred to {@link #resolveAll}: a consumer registers its value sources first, then
 * asks once.
 *
 * <pre>{@code
 * Map<String, ShopEntryAsset> catalogue = ShopAssetStore.getInstance().resolveAll(mySources);
 * }</pre>
 *
 * <p>The store is process-wide because the ASSETS are: one folder, one set of files, however many
 * mods sell out of them. Two FILES landing on one id is reported here, naming both, because in that
 * case one of them simply never exists.
 *
 * <p>Authored offers are already whole when they arrive: the engine's own asset loading resolved
 * their {@code Parent} chains as the files were read. Only GENERATED bodies are decoded here, and
 * they go through the identical codec against the identical parent, so nothing about a generated
 * offer is a special case.
 */
public final class ShopAssetStore {

    /** What one produced entry is called in a message written for the author. */
    private static final String NOUN = "offer";

    private static final ShopAssetStore INSTANCE = new ShopAssetStore();

    @Nonnull
    public static ShopAssetStore getInstance() {
        return INSTANCE;
    }

    /** What a fold produced, and everything worth reporting about it. */
    public record Resolution(@Nonnull Map<String, ShopEntryAsset> entries, @Nonnull List<Finding> issues) {

        public Resolution {
            entries = Map.copyOf(entries);
            issues = List.copyOf(issues);
        }
    }

    private final Map<String, ShopEntryAsset> entries = new ConcurrentHashMap<>();
    private final Map<String, ShopEntryGeneratorAsset> generators = new ConcurrentHashMap<>();
    /** What the last {@link #mergeEntries} noticed about the layer itself, replayed by every fold. */
    private final List<Finding> layerFindings = new ArrayList<>();

    private ShopAssetStore() {
    }

    /** Rebuild the offer layer from a load event's decoded assets. Idempotent on re-import. */
    public synchronized void mergeEntries(@Nonnull Map<String, ShopEntryAsset> layer) {
        entries.clear();
        layerFindings.clear();
        for (Map.Entry<String, ShopEntryAsset> e : layer.entrySet()) {
            ShopEntryAsset asset = e.getValue();
            if (e.getKey() == null || asset == null) {
                continue;
            }
            String id = asset.getId() == null || asset.getId().isBlank()
                    ? e.getKey().toLowerCase(Locale.ROOT)
                    : asset.getId().toLowerCase(Locale.ROOT);
            ShopEntryAsset clash = entries.put(id, asset);
            if (clash != null) {
                layerFindings.add(Finding.error(ShopValidator.DOMAIN, "DUPLICATE_ENTRY_ID",
                        "two files both resolve to the offer id '" + id + "', so only one of them is ever on "
                                + "sale. Rename one of them", id));
            }
        }
    }

    /** Rebuild the generator layer from a load event's decoded assets. Idempotent on re-import. */
    public synchronized void mergeGenerators(@Nonnull Map<String, ShopEntryGeneratorAsset> layer) {
        generators.clear();
        for (Map.Entry<String, ShopEntryGeneratorAsset> e : layer.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                generators.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
    }

    /** Unmodifiable view of the loaded offer assets, keyed by id (skeletons included). */
    @Nonnull
    public Map<String, ShopEntryAsset> assets() {
        return Collections.unmodifiableMap(entries);
    }

    /** Unmodifiable view of the loaded generators, keyed by id. */
    @Nonnull
    public Map<String, ShopEntryGeneratorAsset> generators() {
        return Collections.unmodifiableMap(generators);
    }

    /** The folded catalogue, findings logged. */
    @Nonnull
    public Map<String, ShopEntryAsset> resolveAll(@Nullable GeneratorCore.AxisValueSource values) {
        Resolution resolution = resolve(values);
        logIssues(resolution.issues());
        return resolution.entries();
    }

    /**
     * Fold every authored offer plus everything the generators write into one catalogue.
     *
     * <p>An authored file always beats a generated offer of the same id, so a family member that
     * needs to be special is made special by writing the file - and the collision is reported, since
     * the other possibility is that a generator's {@code IdPattern} is too coarse.
     *
     * @param values where an axis naming a {@code Source} gets its rows; null means none are
     *               registered, which the findings say rather than silently writing nothing
     */
    @Nonnull
    public Resolution resolve(@Nullable GeneratorCore.AxisValueSource values) {
        List<Finding> issues = new ArrayList<>(layerFindings);
        Map<String, ShopEntryAsset> out = new LinkedHashMap<>();

        List<String> authoredIds = new ArrayList<>(entries.keySet());
        Collections.sort(authoredIds);
        for (String id : authoredIds) {
            ShopEntryAsset asset = entries.get(id);
            if (asset == null || asset.isAbstract()) {
                continue;
            }
            out.put(id, asset);
        }

        List<String> generatorIds = new ArrayList<>(generators.keySet());
        Collections.sort(generatorIds);
        for (String generatorId : generatorIds) {
            ShopEntryGeneratorAsset generator = generators.get(generatorId);
            if (generator == null) {
                continue;
            }
            GeneratorCore.Expansion expansion =
                    GeneratorCore.expand(generator, ShopValidator.DOMAIN, NOUN, values);
            issues.addAll(expansion.issues());
            for (GeneratedBody body : expansion.bodies()) {
                ShopEntryAsset base = entries.get(body.baseId());
                if (base == null) {
                    issues.add(Finding.error(ShopValidator.DOMAIN, "UNKNOWN_BASE",
                            "Base '" + body.baseId() + "' is not an offer anybody authored, so '" + body.id()
                                    + "' inherits nothing and is skipped", generatorId));
                    continue;
                }
                if (entries.containsKey(body.id())) {
                    issues.add(Finding.error(ShopValidator.DOMAIN, "ID_COLLISION",
                            "'" + body.id() + "' is also authored as its own offer file, which wins; either "
                                    + "delete the file or widen IdPattern so the two stop colliding",
                            generatorId));
                    continue;
                }
                if (out.containsKey(body.id())) {
                    issues.add(Finding.error(ShopValidator.DOMAIN, "ID_COLLISION",
                            "two generators both produce '" + body.id() + "', so only the first one exists",
                            generatorId));
                    continue;
                }
                ShopEntryAsset decoded = decodeGenerated(body, base, generatorId, issues);
                if (decoded == null || decoded.isAbstract()) {
                    continue;
                }
                out.put(body.id(), decoded);
            }
        }
        return new Resolution(out, issues);
    }

    /**
     * Decode one generated body against its base through the SAME codec path the asset store uses
     * for a hand-written child, so inheritance behaves identically. A body that fails to decode is
     * reported and skipped rather than taking the whole fold down.
     */
    @Nullable
    private static ShopEntryAsset decodeGenerated(@Nonnull GeneratedBody body,
            @Nonnull ShopEntryAsset base, @Nonnull String generatorId, @Nonnull List<Finding> issues) {
        try {
            AssetExtraInfo.Data data =
                    new AssetExtraInfo.Data(ShopEntryAsset.class, body.id(), body.baseId());
            ShopEntryAsset decoded = ShopEntryAsset.CODEC.decodeAndInheritJsonAsset(
                    RawJsonReader.fromJsonString(body.body().toString()), base, new AssetExtraInfo<>(data));
            if (decoded == null) {
                issues.add(Finding.error(ShopValidator.DOMAIN, "DECODE_FAILED",
                        "'" + body.id() + "' produced no offer at all; check the Child body against the offer "
                                + "schema", generatorId));
            }
            return decoded;
        } catch (Exception e) {
            issues.add(Finding.error(ShopValidator.DOMAIN, "DECODE_FAILED",
                    "'" + body.id() + "' could not be read as an offer: " + e.getMessage(), generatorId));
            return null;
        }
    }

    /** Log a fold's findings: an error as a warning line, anything else at info. */
    public static void logIssues(@Nonnull List<Finding> issues) {
        ValidationReport.logAll("[commerce] shop content", issues, SafeLog::warn, SafeLog::info);
    }
}
