package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentI18n;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;

import javax.annotation.Nonnull;

/**
 * The library's own sentence family, driven over a fixture catalogue so the LADDER is what is
 * under test: the authored key first, the {@code .any} twin for a targetless step, the per-kind
 * convention line, the shipped default, and null when nothing is shipped at all - plus the
 * qualifier and place wrappers around whatever composed.
 */
class NeutralObjectiveComposerTest {

    private static final String NS = "ziggfreedcommon.progress.";

    /** A composer over exactly the shipped keys a test declares. */
    private static NeutralObjectiveComposer over(Set<String> shippedKeys) {
        return new NeutralObjectiveComposer(shippedKeys::contains);
    }

    private static ObjectiveDef step(String kind, String target, long amount) {
        return ObjectiveDef.builder("step", kind).target(target).amount(amount).build();
    }

    @Test
    void theAnyTwinReadsFirstForATargetlessStep() {
        NeutralObjectiveComposer composer = over(Set.of(
                NS + "objective.kill_entity", NS + "objective.kill_entity.any"));

        Message any = composer.compose(step("KILL_ENTITY", "", 10), null);
        assertEquals(NS + "objective.kill_entity.any", any.getMessageId());

        Message targeted = composer.compose(step("KILL_ENTITY", "Trork", 10), null);
        assertEquals(NS + "objective.kill_entity", targeted.getMessageId());
    }

    @Test
    void theConventionIsOpenAPackAddedKindReadsThroughItsOwnShippedKey() {
        NeutralObjectiveComposer composer = over(Set.of(NS + "objective.yourmod_custom_moment"));

        Message line = composer.compose(step("YOURMOD_CUSTOM_MOMENT", "Something", 3), null);
        assertEquals(NS + "objective.yourmod_custom_moment", line.getMessageId(),
                "the sentence key is a convention on the kind id, never a closed switch");
    }

    @Test
    void theDefaultIsTheLastShippedRungAndNothingShippedComposesNothing() {
        NeutralObjectiveComposer withDefault = over(Set.of(
                NS + "objective.default", NS + "objective.default.any"));
        assertEquals(NS + "objective.default",
                withDefault.compose(step("YOURMOD_UNKNOWN", "Thing", 2), null).getMessageId());
        assertEquals(NS + "objective.default.any",
                withDefault.compose(step("YOURMOD_UNKNOWN", "", 2), null).getMessageId());

        assertNull(over(Set.of()).compose(step("KILL_ENTITY", "Trork", 10), null),
                "with nothing shipped the composer says so, and the authored-key fallback takes over");
    }

    @Test
    void aQualifierPrefixesAndAPlaceSuffixesTheSentence() {
        NeutralObjectiveComposer composer = over(Set.of(
                NS + "objective.kill_entity", NS + "objective.qualifier", NS + "objective.zone"));

        ObjectiveDef step = ObjectiveDef.builder("step", "KILL_ENTITY").target("Trork").amount(5)
                .qualifier("elite").zone("Emerald_Grove").build();
        Message line = composer.compose(step, null);

        assertEquals(NS + "objective.zone", line.getMessageId(),
                "the place wrapper is outermost");
        FormattedMessage inner = param(line, "0");
        assertNotNull(inner);
        assertEquals(NS + "objective.qualifier", inner.messageId,
                "the qualifier wraps the sentence before the place does");
    }

    @Test
    void theAuthoredKeyOutranksTheGeneratedSentenceAndResolvesWithItsArguments() {
        ContentKeys.reset();
        ContentKeys.install(new ContentI18n() {
            @Override
            @Nonnull
            public String keyPrefix() {
                return "fixture.";
            }

            @Override
            public boolean hasKey(@Nonnull String unprefixedKey) {
                return "yourmod.step.collect".equals(unprefixedKey);
            }
        });
        try {
            NeutralObjectiveComposer composer = over(Set.of(NS + "objective.pickup_item"));
            Message line = composer.compose(step("PICKUP_ITEM", "Ore", 64), "yourmod.step.collect");

            assertEquals("fixture.yourmod.step.collect", line.getMessageId(),
                    "the author's own line wins over the generated sentence");
            assertNotNull(param(line, "0"), "and it resolves WITH its arguments, so a {0} slot is "
                    + "never painted literally");
        } finally {
            ContentKeys.reset();
        }
    }

    @Test
    void theInstalledComposerOutranksTheNeutralFamilyAndAThrowingOneCostsOnlyItsFancierWording() {
        ObjectiveDef step = step("KILL_ENTITY", "Trork", 10);
        try {
            ObjectiveComposer.install((objective, key) -> {
                throw new IllegalStateException("a consumer bug");
            });
            assertNull(ObjectiveComposer.line(step, null),
                    "the neutral family answered instead of the throw escaping - null here only "
                            + "because this JVM ships no catalogue for it to read");

            Message theirs = Msg.raw("their words");
            ObjectiveComposer.install((objective, key) -> theirs);
            assertEquals("their words", ObjectiveComposer.line(step, null).getRawText());
        } finally {
            // Leave the seam answering nothing, which is the uninstalled behaviour every other
            // test in this JVM expects.
            ObjectiveComposer.install((objective, key) -> null);
        }
    }

    /** The nested message bound to param {@code name}, or null - a localized arg stays a Message. */
    private static FormattedMessage param(Message message, String name) {
        FormattedMessage formatted = message.getFormattedMessage();
        return formatted.messageParams == null ? null : formatted.messageParams.get(name);
    }
}
