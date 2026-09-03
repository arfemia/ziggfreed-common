package com.ziggfreed.common.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;


/**
 * The library's own sentence family, driven over a fixture catalogue so the LADDER is what is
 * under test: the authored key first, the {@code .any} twin for a targetless step, the per-kind
 * convention line, the shipped default, and null when nothing is shipped at all - plus the
 * qualifier and place wrappers around whatever composed.
 */
class NeutralObjectiveComposerTest {

    private static final String NS = "ziggfreedcommon.progress.";

    /** A composer over exactly the shipped keys a test declares, with no registered kind vocabulary. */
    private static NeutralObjectiveComposer over(Set<String> shippedKeys) {
        return over(shippedKeys, kindId -> null);
    }

    /** A composer over exactly the shipped keys and the kind lookup a test declares. */
    private static NeutralObjectiveComposer over(Set<String> shippedKeys,
            Function<String, ObjectiveKind> kindLookup) {
        return new NeutralObjectiveComposer(shippedKeys::contains, kindLookup);
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
        LangCatalog.overrideForTests(Map.of("fixture.yourmod.step.collect", "Collect {0} of {1}"));
        try {
            NeutralObjectiveComposer composer = over(Set.of(NS + "objective.pickup_item"));
            Message line = composer.compose(step("PICKUP_ITEM", "Ore", 64), "yourmod.step.collect");

            assertEquals("fixture.yourmod.step.collect", line.getMessageId(),
                    "the author's own line wins over the generated sentence");
            assertNotNull(param(line, "0"), "and it resolves WITH its arguments, so a {0} slot is "
                    + "never painted literally");
        } finally {
            LangCatalog.overrideForTests(null);
        }
    }

    @Test
    void theKindsOwnTextKeyReadsWhenTheStepAuthoredNoneAndTheRegistryKnowsTheKind() {
        LangCatalog.overrideForTests(Map.of(
                "rpgstations.objective.text.work_station", "Work {0} cycles at {1}",
                "rpgstations.objective.text.work_station.any", "Work {0} cycles at any station"));
        try {
            ObjectiveKind kind = ObjectiveKind.of("WORK_STATION").withPresentation(
                    new ObjectiveKind.Presentation("objective.text.work_station", null, Map.of()));
            NeutralObjectiveComposer composer = over(Set.of(), kindId -> kind);

            Message targeted = composer.compose(step("WORK_STATION", "Sawmill", 5), null);
            assertEquals("rpgstations.objective.text.work_station", targeted.getMessageId(),
                    "the registered kind's own TextKey answers before the library's convention rung");

            Message any = composer.compose(step("WORK_STATION", "", 5), null);
            assertEquals("rpgstations.objective.text.work_station.any", any.getMessageId(),
                    "and its .any twin answers a targetless step, the same as the convention rung does");
        } finally {
            LangCatalog.overrideForTests(null);
        }
    }

    @Test
    void aKindWithNoTextKeyFallsThroughToTheConventionKeyAsToday() {
        ObjectiveKind kind = ObjectiveKind.of("KILL_ENTITY"); // Presentation.NONE: no TextKey authored
        NeutralObjectiveComposer composer = over(Set.of(NS + "objective.kill_entity"), kindId -> kind);

        Message line = composer.compose(step("KILL_ENTITY", "Trork", 10), null);
        assertEquals(NS + "objective.kill_entity", line.getMessageId(),
                "a registered kind that names no TextKey falls through to the convention rung unchanged");
    }

    @Test
    void aKindTheRegistryDoesNotKnowFallsThroughUnchanged() {
        NeutralObjectiveComposer composer = over(Set.of(NS + "objective.kill_entity"), kindId -> null);

        Message line = composer.compose(step("KILL_ENTITY", "Trork", 10), null);
        assertEquals(NS + "objective.kill_entity", line.getMessageId(),
                "an id the registry does not know reads through to the convention rung exactly as "
                        + "before this seam existed");
    }

    @Test
    void theStepsOwnAuthoredKeyStillOutranksTheKindsTextKey() {
        LangCatalog.overrideForTests(Map.of(
                "fixture.yourmod.step.custom", "Custom words {0} {1}",
                "rpgstations.objective.text.work_station", "Work {0} cycles at {1}"));
        try {
            ObjectiveKind kind = ObjectiveKind.of("WORK_STATION").withPresentation(
                    new ObjectiveKind.Presentation("objective.text.work_station", null, Map.of()));
            NeutralObjectiveComposer composer = over(Set.of(), kindId -> kind);

            Message line = composer.compose(step("WORK_STATION", "Sawmill", 5), "yourmod.step.custom");
            assertEquals("fixture.yourmod.step.custom", line.getMessageId(),
                    "the step's own authored key still outranks the kind's generated template");
        } finally {
            LangCatalog.overrideForTests(null);
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
