package com.ziggfreed.common.quest.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonPrimitive;
import com.ziggfreed.common.progress.asset.GeneratedBody;
import com.ziggfreed.common.progress.asset.GeneratorCore;
import com.ziggfreed.common.validation.Finding;

/**
 * Turns a {@link QuestGeneratorAsset} into the quest bodies it describes.
 *
 * <p>The walk, the substitution contract and the findings all live in the shared
 * {@link GeneratorCore}, which every content type that writes a family from one file uses; this
 * class is the QUEST half of it - the enumerator registry adapter, and the quest words a finding is
 * phrased in.
 *
 * <p>It MERGES NOTHING. Each body comes out as ordinary quest JSON carrying {@code Parent}, and the
 * inheritance that follows is the same one a hand-written child gets, which is what makes a
 * generated quest indistinguishable from an authored one.
 */
public final class QuestGeneratorExpander {

    /** What one produced entry is called in a message written for the author. */
    private static final String NOUN = "quest";

    /** What one generator produced, and everything worth reporting about it. */
    public record Expansion(@Nonnull List<GeneratedQuestBody> bodies, @Nonnull List<Finding> issues) {

        /** Nothing produced, nothing to report. */
        public static final Expansion EMPTY = new Expansion(List.of(), List.of());

        public Expansion {
            bodies = List.copyOf(bodies);
            issues = List.copyOf(issues);
        }
    }

    private QuestGeneratorExpander() {
    }

    /**
     * Expand {@code generator}.
     *
     * @param enumerators the registered value sources an axis may name; null means none are
     * @return the bodies to inject into the quest pool, plus any findings
     */
    @Nonnull
    public static Expansion expand(@Nonnull QuestGeneratorAsset generator,
            @Nullable QuestEnumeratorRegistry enumerators) {

        GeneratorCore.Expansion expansion = GeneratorCore.expand(generator,
                QuestPoolValidator.DOMAIN, NOUN, axisValues(enumerators));

        List<GeneratedQuestBody> bodies = new ArrayList<>(expansion.bodies().size());
        for (GeneratedBody body : expansion.bodies()) {
            bodies.add(new GeneratedQuestBody(body.id(), body.body(), body.baseId(), body.generatorId()));
        }
        return new Expansion(bodies, expansion.issues());
    }

    /**
     * The registered value sources as the shared core reads them, so ONE registered vocabulary
     * ({@code "yourmod:ores"}, {@code "yourmod:regions"}) serves every store that walks axes rather
     * than each store asking a consumer to register the same list again.
     *
     * <p>Public because another content type's generator resolves through the same registry; a null
     * registry answers "nothing is registered", which the core reports rather than treating as an
     * error.
     */
    @Nullable
    public static GeneratorCore.AxisValueSource axisValues(@Nullable QuestEnumeratorRegistry enumerators) {
        if (enumerators == null) {
            return null;
        }
        return new GeneratorCore.AxisValueSource() {

            @Override
            public boolean isRegistered(@Nullable String sourceId) {
                return enumerators.isRegistered(sourceId);
            }

            @Override
            @Nonnull
            public List<Map<String, JsonPrimitive>> rows(@Nonnull String sourceId, @Nullable String token,
                    @Nonnull Map<String, String> filter) {
                QuestValueEnumerator enumerator = enumerators.enumerator(sourceId);
                if (enumerator == null) {
                    return List.of();
                }
                List<Map<String, JsonPrimitive>> out = new ArrayList<>();
                for (QuestAxisRow row : enumerator.rows(filter)) {
                    if (row != null && !row.isEmpty()) {
                        out.add(row.bind(token));
                    }
                }
                return out;
            }

            @Override
            public void recordFailure(@Nullable String sourceId, @Nullable String message) {
                enumerators.recordFailure(sourceId, message);
            }
        };
    }
}
