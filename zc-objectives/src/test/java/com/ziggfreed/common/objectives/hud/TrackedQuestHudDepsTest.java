package com.ziggfreed.common.objectives.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * The tracker's deps: an unfilled seam leaves the NATIVE look and a working tracker; a filled seam
 * changes exactly what it was asked to and nothing beside it; and a filled seam that fails costs
 * its own answer, never the tracker.
 *
 * <p>The colours are asserted against the deps' own named native constants rather than literal
 * hexes, because what is being pinned is "the default IS the native value the class declares",
 * not any particular number.
 */
class TrackedQuestHudDepsTest {

    private final Subject player = Subject.of(UUID.randomUUID(), "tester");

    @AfterEach
    void clearRegisteredDeps() {
        TrackedQuestHuds.deps(null);
    }

    // ==================== the theme seam ====================

    @Test
    void anEmptyThemeLeavesTheNativeLook() {
        TrackedQuestHudDeps deps = TrackedQuestHudDeps.DEFAULTS;
        assertSame(TrackedQuestHudDeps.NATIVE_LOOK, deps.theme(), "no theme paints nothing over the native chrome");
        assertEquals(TrackedQuestHudDeps.NATIVE_TASK_IN_PROGRESS, deps.taskColor(false));
        assertEquals(TrackedQuestHudDeps.NATIVE_TASK_COMPLETE, deps.taskColor(true));
        assertEquals(TrackedQuestHudDeps.NATIVE_COUNT_IN_PROGRESS, deps.countColor(false));
        assertEquals(TrackedQuestHudDeps.NATIVE_COUNT_COMPLETE, deps.countColor(true));
    }

    @Test
    void aFilledThemeChangesTheColourItWasAskedToAndNoOther() {
        TrackedQuestHudDeps deps = TrackedQuestHudDeps.builder()
                .taskColorInProgress("#123456")
                .build();
        assertEquals("#123456", deps.taskColor(false));
        assertEquals(TrackedQuestHudDeps.NATIVE_TASK_COMPLETE, deps.taskColor(true));
        assertEquals(TrackedQuestHudDeps.NATIVE_COUNT_IN_PROGRESS, deps.countColor(false));
        assertEquals(TrackedQuestHudDeps.NATIVE_COUNT_COMPLETE, deps.countColor(true));
    }

    @Test
    void everyColourKnobIsIndependent() {
        TrackedQuestHudDeps deps = TrackedQuestHudDeps.builder()
                .taskColorInProgress("#111111")
                .taskColorComplete("#222222")
                .countColorInProgress("#333333")
                .countColorComplete("#444444")
                .build();
        assertEquals("#111111", deps.taskColor(false));
        assertEquals("#222222", deps.taskColor(true));
        assertEquals("#333333", deps.countColor(false));
        assertEquals("#444444", deps.countColor(true));
    }

    @Test
    void aFilledThemeIsTheOneHandedBack() {
        TrackedQuestHudDeps.HudTheme theme = (cmd, root) -> { };
        assertSame(theme, TrackedQuestHudDeps.builder().theme(theme).build().theme());
    }

    // ==================== the other seams and their guards ====================

    @Test
    void theDefaultsShowEveryoneAlwaysInTheNativeCorner() {
        TrackedQuestHudDeps deps = TrackedQuestHudDeps.DEFAULTS;
        assertTrue(deps.isEnabled());
        assertTrue(deps.wantsHud(player));
        assertSame(TrackedQuestHudDeps.DEFAULT_POSITION, deps.position());
    }

    @Test
    void aFilledSeamIsTheOneThatIsAsked() {
        HudPosition moved = new HudPosition(HudPosition.AnchorEdge.BOTTOM, HudPosition.HorizontalEdge.LEFT, 8, 9);
        TrackedQuestHudDeps deps = TrackedQuestHudDeps.builder()
                .position(() -> moved)
                .enabled(() -> false)
                .audience(subject -> subject.id().equals(player.id()))
                .build();
        assertSame(moved, deps.position());
        assertFalse(deps.isEnabled());
        assertTrue(deps.wantsHud(player));
        assertFalse(deps.wantsHud(Subject.of(UUID.randomUUID(), "other")));
    }

    @Test
    void aSeamThatThrowsCostsItsOwnAnswerAndNotTheTracker() {
        TrackedQuestHudDeps deps = TrackedQuestHudDeps.builder()
                .position(() -> {
                    throw new IllegalStateException("layout file unreadable");
                })
                .enabled(() -> {
                    throw new IllegalStateException("config not loaded");
                })
                .audience(subject -> {
                    throw new IllegalStateException("no player data");
                })
                .build();
        assertSame(TrackedQuestHudDeps.DEFAULT_POSITION, deps.position(), "the native corner");
        assertTrue(deps.isEnabled(), "in doubt, on");
        assertTrue(deps.wantsHud(player), "in doubt, shown");
    }

    @Test
    void aSeamAnsweringNullFallsBackToTheDefault() {
        TrackedQuestHudDeps deps = TrackedQuestHudDeps.builder().position(() -> null).build();
        assertSame(TrackedQuestHudDeps.DEFAULT_POSITION, deps.position());
    }

    @Test
    void clearingASeamGoesBackToTheLibraryDefaultRatherThanToNull() {
        TrackedQuestHudDeps deps = TrackedQuestHudDeps.builder()
                .theme(null).audience(null).position(null).enabled(null)
                .taskColorInProgress(null).taskColorComplete(null)
                .countColorInProgress(null).countColorComplete(null)
                .build();
        assertSame(TrackedQuestHudDeps.NATIVE_LOOK, deps.theme());
        assertSame(TrackedQuestHudDeps.EVERYONE, deps.audience());
        assertSame(TrackedQuestHudDeps.DEFAULT_POSITION, deps.position());
        assertTrue(deps.isEnabled());
        assertEquals(TrackedQuestHudDeps.NATIVE_TASK_IN_PROGRESS, deps.taskColor(false));
    }

    // ==================== the registration ====================

    @Test
    void nothingRegisteredResolvesToTheDefaults() {
        assertSame(TrackedQuestHudDeps.DEFAULTS, TrackedQuestHuds.resolvedDeps());
    }

    @Test
    void aRegisteredSupplierIsAskedAtResolveTimeAndAFailingOneCostsOnlyItself() {
        TrackedQuestHudDeps mine = TrackedQuestHudDeps.builder().enabled(() -> false).build();
        TrackedQuestHuds.deps(() -> mine);
        assertSame(mine, TrackedQuestHuds.resolvedDeps());

        TrackedQuestHuds.deps(() -> {
            throw new IllegalStateException("not built yet");
        });
        assertSame(TrackedQuestHudDeps.DEFAULTS, TrackedQuestHuds.resolvedDeps());

        TrackedQuestHuds.deps(() -> null);
        assertSame(TrackedQuestHudDeps.DEFAULTS, TrackedQuestHuds.resolvedDeps());
    }
}
