package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;

/**
 * A {@code Once} with a {@code Period}, end to end through the engine on a stepped clock: a daily
 * line comes back when the UTC day turns over, a daily first-visit beat plays once a day, a click is
 * judged by the day it lands in, and a spend keeps one key per line rather than one per day.
 */
class DialogueOncePeriodSpendTest {

    private static final String JACK = """
            { "Start": { "First": [ { "Node": "hello", "Once": { "Period": "Daily" } } ],
                         "Fallback": "menu" },
              "Nodes": {
                "hello": { "Options": [ { "LabelKey": "hello.back", "Goto": "menu" } ] },
                "menu": { "Options": [
                  { "LabelKey": "menu.candy", "OnceId": "daily_candy", "Once": { "Period": "Daily" } },
                  { "LabelKey": "menu.tale", "Once": true },
                  { "LabelKey": "menu.odd", "OnceId": "PD20000", "Once": true } ] } } }
            """;

    private final long[] now = {at("2026-10-31T12:00:00Z")};

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    @Nonnull
    private DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).clock(() -> now[0]).build();
    }

    private static long at(@Nonnull String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    private static long day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay();
    }

    @Nonnull
    private static DialogueOption menuLine(@Nonnull NpcDialogue d, int index) {
        return d.getNode("menu").getOptions().get(index);
    }

    @Test
    void aDailyLineIsOfferedAgainOnceTheUtcDayTurnsOver() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("old_jack", JACK);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);
        DialogueOption candy = menuLine(d, 0);

        assertTrue(engine.optionAvailable(d, "menu", candy, ctx));
        engine.consumeOnce(null, d, "menu", candy, ctx);
        assertEquals(Set.of("once:o:old_jack:menu:daily_candy:PD" + day(2026, 10, 31)), ctx.state().keys);
        assertFalse(engine.optionAvailable(d, "menu", candy, ctx), "spent for the rest of the day");

        now[0] = at("2026-10-31T23:59:59Z");
        assertFalse(engine.optionAvailable(d, "menu", candy, ctx), "still the same UTC day");

        now[0] = at("2026-11-01T00:00:00Z");
        assertTrue(engine.optionAvailable(d, "menu", candy, ctx), "a new UTC day offers it again");
    }

    @Test
    void theCleanupReachesOnlyThisLinesEarlierWindows() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("old_jack", JACK);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);
        // Spent before its author made it daily, plus two neighbours spent for good, one of them
        // with an id that reads like a window segment.
        String permanentCandy = "once:o:old_jack:menu:daily_candy";
        ctx.state().set(permanentCandy);
        assertTrue(engine.optionAvailable(d, "menu", menuLine(d, 0), ctx),
                "a key from before the line turned daily does not hold the daily line back");
        engine.consumeOnce(null, d, "menu", menuLine(d, 1), ctx);
        engine.consumeOnce(null, d, "menu", menuLine(d, 2), ctx);

        engine.consumeOnce(null, d, "menu", menuLine(d, 0), ctx);
        now[0] = at("2026-11-01T09:00:00Z");
        engine.consumeOnce(null, d, "menu", menuLine(d, 0), ctx);

        assertEquals(Set.of(permanentCandy,
                        "once:o:old_jack:menu:menu.tale",
                        "once:o:old_jack:menu:pd20000",
                        "once:o:old_jack:menu:daily_candy:PD" + day(2026, 11, 1)),
                ctx.state().keys,
                "one window kept for the daily line; its old key and both neighbours untouched");
    }

    @Test
    void aDailyFirstVisitBeatPlaysOnceADay() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("old_jack", JACK);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueEngine.EntryResolution first = engine.resolveEntry(d, ctx);
        assertEquals("hello", first.nodeId());
        assertEquals("once:e:old_jack:hello:PD" + day(2026, 10, 31), first.onceKey());
        assertEquals("once:e:old_jack:hello:P", first.onceFamily());
        engine.consumeOnce(first.onceKey(), first.onceFamily(), d, "hello",
                d.getNode("hello").getOptions().get(0), ctx);
        assertEquals("menu", engine.resolveEntry(d, ctx).nodeId(),
                "played through, the rest of the day opens on the menu");

        now[0] = at("2026-11-01T08:00:00Z");
        DialogueEngine.EntryResolution next = engine.resolveEntry(d, ctx);
        assertEquals("hello", next.nodeId(), "a new day greets again");
        engine.consumeOnce(next.onceKey(), next.onceFamily(), d, "hello", null, ctx);
        assertEquals(Set.of("once:e:old_jack:hello:PD" + day(2026, 11, 1)), ctx.state().keys,
                "the beat keeps one key, the current day's");
    }

    @Test
    void aClickAfterMidnightIsCheckedAgainstTheNewDay() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("old_jack", JACK);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);
        DialogueOption candy = menuLine(d, 0);

        now[0] = at("2026-10-31T23:59:58Z");
        engine.consumeOnce(null, d, "menu", candy, ctx);
        assertFalse(engine.optionAvailable(d, "menu", candy, ctx), "drawn a moment before midnight: spent");

        now[0] = at("2026-11-01T00:00:01Z");
        assertTrue(engine.optionAvailable(d, "menu", candy, ctx),
                "the click re-check asks about the day the click lands in");
        engine.consumeOnce(null, d, "menu", candy, ctx);
        assertFalse(engine.optionAvailable(d, "menu", candy, ctx), "and only once");
        assertEquals(Set.of("once:o:old_jack:menu:daily_candy:PD" + day(2026, 11, 1)), ctx.state().keys);
    }

    @Test
    void theFiveArgumentSpendStillWritesADailyBeat() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("old_jack", JACK);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueEngine.EntryResolution first = engine.resolveEntry(d, ctx);
        engine.consumeOnce(first.onceKey(), d, "hello", null, ctx);
        assertTrue(ctx.state().has(first.onceKey()), "a caller that predates the family still spends the beat");
        assertEquals("menu", engine.resolveEntry(d, ctx).nodeId());
    }
}
