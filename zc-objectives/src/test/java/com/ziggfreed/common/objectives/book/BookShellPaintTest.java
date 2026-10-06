package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.achievement.AchievementsTab;
import com.ziggfreed.common.objectives.book.quest.QuestJournalTab;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.Stat;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * What the shell's own painters send: the header writes only the band's ids, a tab paints inside its document's
 * root, and a build answers nothing (the build is the answer). Tagged {@code engine-items}: a
 * {@link UICommandBuilder}'s static init reaches the engine's item codec, which needs the engine's log manager.
 */
@Tag("engine-items")
class BookShellPaintTest {

    /** A host that must never be asked to answer during a build. */
    private static final class BuildHost implements BookContext.Host {

        int answers;

        @Override
        public void send(@Nullable UICommandBuilder cmd, @Nullable UIEventBuilder events) {
            answers++;
        }

        @Override
        public void reopen(@Nonnull BookState next) {
            answers++;
        }

        @Override
        public void close() {
            answers++;
        }

        @Override
        public void toast(@Nonnull ToastSpec spec) {
        }

        @Override
        public boolean openDestination(@Nonnull Destination destination) {
            answers++;
            return true;
        }

        @Override
        public void keep(@Nonnull BookState state) {
        }

        @Nullable
        @Override
        public Subject subject(boolean achievements) {
            return null;
        }
    }

    @Nonnull
    private static List<String> selectors(@Nonnull UICommandBuilder cmd) {
        List<String> out = new ArrayList<>();
        for (CustomUICommand c : cmd.getCommands()) {
            if (c.selector != null) {
                out.add(c.selector);
            }
        }
        return out;
    }

    @Nonnull
    private static BookContext build(@Nonnull BuildHost host, @Nonnull BookTab tab, @Nonnull UICommandBuilder cmd) {
        return BookContext.forBuild(host, tab, BookState.of(tab.id()), ObjectiveBookDeps.DEFAULTS, cmd,
                new UIEventBuilder(), null, null, null, null, 0L);
    }

    @Test
    void theHeaderWritesOnlyTheBandsIds() {
        UICommandBuilder cmd = new UICommandBuilder();
        BookContext ctx = build(new BuildHost(), new QuestJournalTab(), cmd);

        ctx.header().subtitle(Msg.raw("3 in progress"));
        ctx.header().stats(List.of(new Stat(Msg.num(3), Msg.raw("In progress"), Tone.ACTIVE)));
        ctx.header().stat(LedgerLayout.STATS, new Stat(Msg.num(1), Msg.raw("ignored"), Tone.NEUTRAL));

        List<String> sent = selectors(cmd);
        assertTrue(sent.contains(BookHeader.SUBTITLE + ".TextSpans"));
        assertTrue(sent.contains(BookHeader.statSelector(0) + ".Visible"));
        assertTrue(sent.contains(BookHeader.statSelector(1) + ".Visible"), "an unfilled stat is hidden");
        assertTrue(sent.contains(BookHeader.statSelector(2) + ".Visible"));
        for (String selector : sent) {
            assertTrue(selector.startsWith(BookHeader.SUBTITLE) || selector.startsWith("#Stat0")
                    || selector.startsWith("#Stat1") || selector.startsWith("#Stat2"),
                    "the header writes only its band: " + selector);
            assertFalse(selector.startsWith("#Stat" + LedgerLayout.STATS), "a stat past the band is ignored");
        }
    }

    @Test
    void aPlaceholderTabPaintsInsideItsOwnDocument() {
        for (BookTab tab : List.of(new QuestJournalTab(), new AchievementsTab())) {
            UICommandBuilder cmd = new UICommandBuilder();
            BuildHost host = new BuildHost();
            tab.build(build(host, tab, cmd));
            List<String> sent = selectors(cmd);
            assertFalse(sent.isEmpty(), tab.id() + " paints its empty state");
            for (String selector : sent) {
                assertTrue(selector.startsWith(BookContext.BODY + " #"), tab.id() + " paints inside its root: "
                        + selector);
            }
            assertEquals(0, host.answers, "a build answers nothing");
        }
    }

    @Test
    void aBuildContextRefusesToAnswer() {
        BuildHost host = new BuildHost();
        BookContext ctx = build(host, new QuestJournalTab(), new UICommandBuilder());
        ctx.reopen(BookState.of(null));
        ctx.sendPartial();
        assertFalse(ctx.openDestination(new Destination() {
        }));
        ctx.answerIfSilent();
        assertEquals(0, host.answers, "the build is the answer");
    }
}
