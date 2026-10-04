package com.ziggfreed.common.feedback;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.ziggfreed.common.i18n.Msg;

/**
 * What {@link EventTitles} hands the engine, recorded through its package-private sink, since a unit JVM
 * has no player to send a banner to: the engine style each banner size picks, every engine style a style
 * form names, the timing each short form sends, the sound that follows a banner, that the second line is
 * never null on the wire, and that a failing engine never reaches the caller. The engine call itself is
 * proved by the compile on Update 7, the bytecode check in the leg gate and the in-game smoke. No test
 * builds a {@code PlayerRef}: the recorders never read it, so null stands in.
 */
class EventTitlesTest {

    private static final Message HEADLINE = Msg.raw("Headline");
    private static final Message LINE = Msg.raw("Second line");

    /** One banner as the engine would receive it. */
    private record Shown(@Nonnull Message primary, @Nonnull Message secondary, @Nonnull EventTitleStyle style,
                         @Nullable String icon, float duration, float fadeIn, float fadeOut) {
    }

    /** Records what reaches the engine. */
    private static final class Recorder implements EventTitles.Sink {

        final List<Shown> shown = new ArrayList<>();
        final List<Float> hidden = new ArrayList<>();

        @Override
        public void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary, @Nonnull Message secondary,
                         @Nonnull EventTitleStyle style, @Nullable String icon, float duration, float fadeIn,
                         float fadeOut) {
            shown.add(new Shown(primary, secondary, style, icon, duration, fadeIn, fadeOut));
        }

        @Override
        public void hide(@Nonnull PlayerRef playerRef, float fadeOut) {
            hidden.add(fadeOut);
        }
    }

    /** An engine that refuses every call: a runtime failure, or a method the running server lacks. */
    private static final class Refusing implements EventTitles.Sink {

        private final RuntimeException runtime;
        private final Error error;

        Refusing(@Nonnull RuntimeException runtime) {
            this.runtime = runtime;
            this.error = null;
        }

        Refusing(@Nonnull Error error) {
            this.runtime = null;
            this.error = error;
        }

        private void refuse() {
            if (error != null) {
                throw error;
            }
            throw runtime;
        }

        @Override
        public void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary, @Nonnull Message secondary,
                         @Nonnull EventTitleStyle style, @Nullable String icon, float duration, float fadeIn,
                         float fadeOut) {
            refuse();
        }

        @Override
        public void hide(@Nonnull PlayerRef playerRef, float fadeOut) {
            refuse();
        }

        @Override
        public void sound(@Nonnull PlayerRef playerRef, @Nonnull String soundEventId) {
            refuse();
        }
    }

    @Test
    void aMajorBannerIsDrawnInTheEnginesMajorStyle() {
        Recorder engine = new Recorder();

        EventTitles.showVia(engine, null, HEADLINE, LINE, true);

        assertEquals(EventTitleStyle.Major, engine.shown.get(0).style());
    }

    @Test
    void anOrdinaryBannerIsDrawnInTheEnginesDefaultStyle() {
        Recorder engine = new Recorder();

        EventTitles.showVia(engine, null, HEADLINE, LINE, false);

        assertEquals(EventTitleStyle.Default, engine.shown.get(0).style());
    }

    @Test
    void theShortFormSendsTheEnginesDefaultTimingAndNoIconForBothSizes() {
        Recorder engine = new Recorder();

        EventTitles.showVia(engine, null, HEADLINE, LINE, true);
        EventTitles.showVia(engine, null, HEADLINE, LINE, false);

        assertEquals(2, engine.shown.size());
        for (Shown banner : engine.shown) {
            assertNull(banner.icon(), "the short form shows no icon");
            assertEquals(EventTitles.DEFAULT_DURATION, banner.duration());
            assertEquals(EventTitles.DEFAULT_FADE_IN, banner.fadeIn(), "the same fade-in for both sizes");
            assertEquals(EventTitles.DEFAULT_FADE_OUT, banner.fadeOut());
        }
    }

    @Test
    void theDefaultsAreTheEnginesOwn() {
        assertEquals(EventTitleUtil.DEFAULT_DURATION, EventTitles.DEFAULT_DURATION);
        assertEquals(EventTitleUtil.DEFAULT_FADE_DURATION, EventTitles.DEFAULT_FADE_IN);
        assertEquals(EventTitleUtil.DEFAULT_FADE_DURATION, EventTitles.DEFAULT_FADE_OUT);
    }

    @Test
    void theLongFormHandsTheCallersIconAndTimingThroughUnchanged() {
        Recorder engine = new Recorder();

        EventTitles.showVia(engine, null, HEADLINE, LINE, false, "Icon_Quest", 6.0F, 0.5F, 2.0F);

        Shown banner = engine.shown.get(0);
        assertEquals("Icon_Quest", banner.icon());
        assertEquals(6.0F, banner.duration());
        assertEquals(0.5F, banner.fadeIn());
        assertEquals(2.0F, banner.fadeOut());
        assertEquals(EventTitleStyle.Default, banner.style());
    }

    @Test
    void theCallersLinesReachTheEngineAsGiven() {
        Recorder engine = new Recorder();

        EventTitles.showVia(engine, null, HEADLINE, LINE, true);

        assertSame(HEADLINE, engine.shown.get(0).primary());
        assertSame(LINE, engine.shown.get(0).secondary());
    }

    @Test
    void aMissingSecondLineStillReachesTheEngineAsALine() {
        Recorder engine = new Recorder();

        EventTitles.showVia(engine, null, HEADLINE, null, true);
        EventTitles.showVia(engine, null, HEADLINE, null, false, null, 4.0F, 1.5F, 1.5F);

        assertEquals(2, engine.shown.size(), "the headline still shows");
        assertNotNull(engine.shown.get(0).secondary(), "the engine's style overload reads the second line");
        assertNotNull(engine.shown.get(1).secondary());
    }

    @Test
    void hideHandsTheCallersFadeToTheEngine() {
        Recorder engine = new Recorder();

        EventTitles.hideVia(engine, null, 0.25F);

        assertEquals(List.of(0.25F), engine.hidden);
    }

    @Test
    void anEngineThatRefusesNeverReachesTheCaller() {
        for (EventTitles.Sink engine : List.of(new Refusing(new IllegalStateException("no packet handler")),
                new Refusing(new NoSuchMethodError("EventTitleUtil.showEventTitleToPlayer")))) {
            assertDoesNotThrow(() -> EventTitles.showVia(engine, null, HEADLINE, LINE, true));
            assertDoesNotThrow(() -> EventTitles.showVia(engine, null, HEADLINE, LINE, false, null, 4.0F, 1.5F,
                    1.5F));
            assertDoesNotThrow(() -> EventTitles.hideVia(engine, null, 1.5F));
        }
    }

    // ==================== the style forms and the sound (M26) ====================

    /**
     * Records banners and the sounds that follow them, each kind on its own and both in {@code calls}, in the
     * order the engine receives them.
     */
    private static class SoundRecorder implements EventTitles.Sink {

        final List<Shown> shown = new ArrayList<>();
        final List<String> sounds = new ArrayList<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public void show(@Nonnull PlayerRef playerRef, @Nonnull Message primary, @Nonnull Message secondary,
                         @Nonnull EventTitleStyle style, @Nullable String icon, float duration, float fadeIn,
                         float fadeOut) {
            shown.add(new Shown(primary, secondary, style, icon, duration, fadeIn, fadeOut));
            calls.add("banner " + style.name());
        }

        @Override
        public void hide(@Nonnull PlayerRef playerRef, float fadeOut) {
        }

        @Override
        public void sound(@Nonnull PlayerRef playerRef, @Nonnull String soundEventId) {
            sounds.add(soundEventId);
            calls.add("sound " + soundEventId);
        }
    }

    /**
     * An engine that draws every banner but refuses its sound, failing the way the given refusing engine
     * fails; the recorder notes each sound asked for before the refusal.
     */
    private static final class SoundRefusing extends SoundRecorder {

        private final Refusing refusing;

        SoundRefusing(@Nonnull Refusing refusing) {
            this.refusing = refusing;
        }

        @Override
        public void sound(@Nonnull PlayerRef playerRef, @Nonnull String soundEventId) {
            super.sound(playerRef, soundEventId);
            refusing.sound(playerRef, soundEventId);
        }
    }

    @Test
    void everyEngineStyleIsDrawnAsAsked() {
        SoundRecorder engine = new SoundRecorder();

        for (EventTitleStyle style : EventTitleStyle.values()) {
            EventTitles.showVia(engine, null, HEADLINE, LINE, style, null);
        }

        assertEquals(List.of(EventTitleStyle.values()), engine.shown.stream().map(Shown::style).toList());
    }

    @Test
    void theStyleShortFormFadesInTheWayTheEngineFadesThatStyleIn() {
        SoundRecorder engine = new SoundRecorder();

        for (EventTitleStyle style : EventTitleStyle.values()) {
            EventTitles.showVia(engine, null, HEADLINE, LINE, style, null);
        }

        for (Shown banner : engine.shown) {
            assertEquals(EventTitleUtil.getDefaultFadeInDuration(banner.style()), banner.fadeIn(),
                    banner.style() + " fades in as the engine fades it in");
            assertEquals(EventTitles.DEFAULT_DURATION, banner.duration());
            assertEquals(EventTitles.DEFAULT_FADE_OUT, banner.fadeOut());
            assertNull(banner.icon());
        }
    }

    @Test
    void aNamedSoundFollowsTheBannerAndABlankOneIsNone() {
        SoundRecorder engine = new SoundRecorder();

        EventTitles.showVia(engine, null, HEADLINE, LINE, EventTitleStyle.GoblinBreach, " SFX_Breach ");
        EventTitles.showVia(engine, null, HEADLINE, LINE, EventTitleStyle.Default, null);
        EventTitles.showVia(engine, null, HEADLINE, LINE, EventTitleStyle.Default, "  ");
        EventTitles.showVia(engine, null, HEADLINE, LINE, EventTitleStyle.Major, "Icon_Quest", 6.0F, 0.5F, 2.0F,
                "SFX_Fanfare");

        assertEquals(4, engine.shown.size(), "every banner shows, with or without a sound");
        assertEquals(List.of("SFX_Breach", "SFX_Fanfare"), engine.sounds);
        assertEquals(List.of("banner GoblinBreach", "sound SFX_Breach", "banner Default", "banner Default",
                "banner Major", "sound SFX_Fanfare"), engine.calls, "a named sound follows its own banner");
    }

    @Test
    void theBooleanFormsPlayNoSound() {
        SoundRecorder engine = new SoundRecorder();

        EventTitles.showVia(engine, null, HEADLINE, LINE, true);
        EventTitles.showVia(engine, null, HEADLINE, LINE, false, null, 4.0F, 1.5F, 1.5F);

        assertEquals(2, engine.shown.size());
        assertEquals(List.of(), engine.sounds);
    }

    @Test
    void aMissingSecondLineStillReachesTheEngineInTheStyleForms() {
        SoundRecorder engine = new SoundRecorder();

        EventTitles.showVia(engine, null, HEADLINE, null, EventTitleStyle.VoidEviction, null);

        assertNotNull(engine.shown.get(0).secondary());
    }

    @Test
    void anEngineThatRefusesNeverReachesTheCallerOfAStyleForm() {
        for (EventTitles.Sink engine : List.of(new Refusing(new IllegalStateException("no packet handler")),
                new Refusing(new NoSuchMethodError("SoundUtil.playSoundEvent2dToPlayer")))) {
            assertDoesNotThrow(() -> EventTitles.showVia(engine, null, HEADLINE, LINE, EventTitleStyle.Major,
                    "SFX_Fanfare"));
            assertDoesNotThrow(() -> EventTitles.showVia(engine, null, HEADLINE, LINE, EventTitleStyle.Default,
                    null, 4.0F, 1.5F, 1.5F, "SFX_Fanfare"));
        }
    }

    @Test
    void aRefusedSoundNeverReachesTheCallerAndItsBannerStillShows() {
        for (Refusing refusing : List.of(new Refusing(new IllegalStateException("no packet handler")),
                new Refusing(new NoSuchMethodError("SoundUtil.playSoundEvent2dToPlayer")))) {
            SoundRefusing engine = new SoundRefusing(refusing);

            assertDoesNotThrow(() -> EventTitles.showVia(engine, null, HEADLINE, LINE, EventTitleStyle.Major,
                    "SFX_Fanfare"));
            assertDoesNotThrow(() -> EventTitles.showVia(engine, null, HEADLINE, LINE, EventTitleStyle.Default,
                    null, 4.0F, 1.5F, 1.5F, "SFX_Fanfare"));

            assertEquals(List.of("banner Major", "sound SFX_Fanfare", "banner Default", "sound SFX_Fanfare"),
                    engine.calls, "each banner reached the engine before its sound was asked for and refused");
        }
    }
}
