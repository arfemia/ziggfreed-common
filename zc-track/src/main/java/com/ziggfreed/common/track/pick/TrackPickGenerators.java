package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.progress.asset.GeneratedBody;
import com.ziggfreed.common.progress.asset.GeneratorCore;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.validation.Finding;

/**
 * Turns the {@link TrackPickGeneratorAsset}s into the picks they write (pure but for the decode): each generated
 * body through zc-progression's {@link GeneratorCore} (the one substitution contract), then decoded against its own
 * {@code Base}, a hand-written pick, by the very codec a hand-written child goes through, so a generated pick is
 * indistinguishable from an authored one. {@link TrackPickConfig} folds the result under the hand-written picks:
 * a file of a generated id replaces it.
 *
 * <p>A body over a {@code Base} the mod gate refused is absent on purpose and says nothing; one over a Base nobody
 * ships ({@link #UNKNOWN_PICK_BASE}), a second generator writing an id already written ({@link #GENERATED_ID_TAKEN})
 * and a body that does not decode ({@link #GENERATED_PICK_UNREADABLE}) are WARNINGs, the core's own findings beside
 * them. A generated pick whose own {@code Requires} names a mod this server lacks is refused like a file, unless a
 * hand-written file of its id stands in its place; one a hand-written file replaces is an INFO line
 * ({@link #GENERATED_ID_SHADOWED}, written by {@link TrackPickConfig}'s fold).
 */
public final class TrackPickGenerators {

    /** What one generated pick is called in a finding written for the author. */
    private static final String NOUN = "pick";

    public static final String UNKNOWN_PICK_BASE = "UNKNOWN_PICK_BASE";
    public static final String GENERATED_ID_TAKEN = "GENERATED_ID_TAKEN";
    public static final String GENERATED_PICK_UNREADABLE = "GENERATED_PICK_UNREADABLE";
    public static final String GENERATED_ID_SHADOWED = "GENERATED_ID_SHADOWED";

    /**
     * The INFO line for a generated pick a hand-written file of the same id replaces ({@link #GENERATED_ID_SHADOWED}):
     * an intended replacement shows in the audit, and an accidental one can be caught there.
     */
    @Nonnull
    public static Finding shadowed(@Nonnull String id) {
        return Finding.info(TrackPicks.DOMAIN, GENERATED_ID_SHADOWED, "track pick '" + id + "' is written by a pick "
                + "generator and by its own file, which replaces the generated pick", id);
    }

    /**
     * What the generators wrote: the picks by lower-cased id, the ids the mod gate refused (to the missing mod), and
     * what the expansion found wrong.
     */
    public record Expansion(@Nonnull Map<String, TrackPickAsset> picks, @Nonnull Map<String, String> refused,
                            @Nonnull List<Finding> issues) {

        public static final Expansion EMPTY = new Expansion(Map.of(), Map.of(), List.of());

        public Expansion {
            picks = Collections.unmodifiableMap(new LinkedHashMap<>(picks));
            refused = Collections.unmodifiableMap(new LinkedHashMap<>(refused));
            issues = List.copyOf(issues);
        }
    }

    private TrackPickGenerators() {
    }

    /**
     * Expand {@code generators}, in id order, over the hand-written picks {@code authored} (by lower-cased id: each
     * body's Base is one of them).
     *
     * @param refusedBases the ids the mod gate refused among the hand-written picks: a family over one says nothing
     * @param values       where an axis naming a {@code Source} reads its rows; null for none registered
     */
    @Nonnull
    public static Expansion expand(@Nonnull Map<String, TrackPickAsset> authored, @Nonnull Set<String> refusedBases,
            @Nonnull Collection<TrackPickGeneratorAsset> generators, @Nullable GeneratorCore.AxisValueSource values) {
        if (generators.isEmpty()) {
            return Expansion.EMPTY;
        }
        List<TrackPickGeneratorAsset> sorted = new ArrayList<>();
        for (TrackPickGeneratorAsset generator : generators) {
            if (generator != null && generator.getId() != null) {
                sorted.add(generator);
            }
        }
        sorted.sort(Comparator.comparing(TrackPickGeneratorAsset::generatorId));
        Map<String, TrackPickAsset> picks = new LinkedHashMap<>();
        Map<String, String> refused = new LinkedHashMap<>();
        List<Finding> issues = new ArrayList<>();
        for (TrackPickGeneratorAsset generator : sorted) {
            String generatorId = generator.generatorId();
            GeneratorCore.Expansion expansion = GeneratorCore.expand(generator, TrackPicks.DOMAIN, NOUN, values);
            issues.addAll(expansion.issues());
            for (GeneratedBody body : expansion.bodies()) {
                String baseId = body.baseId().toLowerCase(Locale.ROOT);
                TrackPickAsset base = authored.get(baseId);
                if (base == null && refusedBases.contains(baseId)) {
                    continue; // the base belongs to a mod this server lacks, so the family is absent on purpose
                }
                if (base == null) {
                    issues.add(Finding.warning(TrackPicks.DOMAIN, UNKNOWN_PICK_BASE, "track pick generator '"
                            + generatorId + "' writes '" + body.id() + "' under the Base '" + body.baseId()
                            + "', which no loaded TrackPicks file is, so that pick is not written", generatorId));
                    continue;
                }
                if (picks.containsKey(body.id()) || refused.containsKey(body.id())) {
                    issues.add(Finding.warning(TrackPicks.DOMAIN, GENERATED_ID_TAKEN, "track pick generator '"
                            + generatorId + "' writes '" + body.id() + "', which an earlier generator already "
                            + "wrote, so only the first one exists; widen IdPattern", generatorId));
                    continue;
                }
                TrackPickAsset decoded = decode(body, base, generatorId, issues);
                if (decoded == null) {
                    continue;
                }
                if (!GateSpec.passesModGate(decoded.requires())) {
                    refused.put(body.id(), GateSpec.missingMod(decoded.requires()));
                    continue;
                }
                picks.put(body.id(), decoded);
            }
        }
        return new Expansion(picks, refused, issues);
    }

    /**
     * One generated body decoded against {@code base} through the same codec path the asset store takes for a
     * hand-written child (its Parent the body's Base), or null (reported) when it does not read.
     */
    @Nullable
    private static TrackPickAsset decode(@Nonnull GeneratedBody body, @Nonnull TrackPickAsset base,
            @Nonnull String generatorId, @Nonnull List<Finding> issues) {
        try {
            AssetExtraInfo.Data data = new AssetExtraInfo.Data(TrackPickAsset.class, body.id(), body.baseId());
            TrackPickAsset decoded = TrackPickAsset.CODEC.decodeAndInheritJsonAsset(
                    RawJsonReader.fromJsonString(body.body().toString()), base, new AssetExtraInfo<>(data));
            if (decoded == null) {
                issues.add(Finding.warning(TrackPicks.DOMAIN, GENERATED_PICK_UNREADABLE, "track pick generator '"
                        + generatorId + "' wrote '" + body.id() + "', which reads as no pick; check its Child "
                        + "against the pick schema", generatorId));
            }
            return decoded;
        } catch (Exception e) {
            issues.add(Finding.warning(TrackPicks.DOMAIN, GENERATED_PICK_UNREADABLE, "track pick generator '"
                    + generatorId + "' wrote '" + body.id() + "', which does not read as a pick: " + e.getMessage(),
                    generatorId));
            return null;
        }
    }
}
