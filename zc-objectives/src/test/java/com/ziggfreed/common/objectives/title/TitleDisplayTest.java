package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.event.IEvent;
import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.entity.title.ZigTitleComponent;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.PlainText;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;

/**
 * What the display seam shows once titles fill it: an offered title around the name, nothing for a
 * player showing none, nothing (but the choice kept) for a title switched off or never defined, and
 * the same title on a row naming a player who is offline, after a restart too, so a title never
 * tells a viewer who is online. No entity store anywhere: the decorator reads the process-wide
 * record and the fold only, which is what lets a page on any world thread name any player.
 *
 * <p>The tests that restart ({@code leaveAndRestart}) read back a file the record wrote through the
 * engine's own atomic writer, which on Update 7 loads only under the engine's log manager, so they
 * alone are tagged {@code engine-items}; {@code ActiveTitlesTest} pins that a write the engine cannot
 * make never reaches the caller.
 */
class TitleDisplayTest {

    private static final UUID ZIG = UUID.randomUUID();

    private static final Subject ZIGGFREED = Subject.of(ZIG, "Ziggfreed");

    @TempDir
    Path dir;

    @BeforeEach
    void titles() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of(
                "Hallows_Eve_Hallowed", TitleAssetTest.title("{}", "Hallows_Eve_Hallowed"),
                "Pumpkin_King", TitleAssetTest.title("{ \"Enabled\": false }", "Pumpkin_King")));
        LangCatalog.overrideForTests(Map.of(
                "ziggfreedcommon.title.display", "{0}, {1}",
                "hallowseve.title.hallows_eve_hallowed.display", "{0} the Hallowed"));
        TitleEvents.publishTo(new NativeEventSeam.Publisher() {
            @Override
            public <E extends IEvent<Void>> void publish(@Nonnull Class<E> type, @Nonnull Supplier<E> build) {
                // a write's announcement is TitleUnlocksTest's business
            }
        });
        ActiveTitles.persistTo(record(), null);
    }

    @AfterEach
    void clear() {
        ActiveTitles.persistTo(null, null);
        ActiveTitles.clear();
        TitleEvents.publishTo(null);
        TitleConfig.getInstance().mergePackLayer(Map.of());
        TitleConfig.getInstance().mergeOwnerLayer(Map.of());
        LangCatalog.overrideForTests(null);
        PlayerDisplayNames.fillDecorator(null);
        PlayerDisplayNames.fillLiveNames(null);
    }

    @Test
    void aPlayerShowingAnOfferedTitleIsNamedWithIt() {
        ActiveTitles.put(ZIG, "Hallows_Eve_Hallowed");
        assertEquals("Ziggfreed the Hallowed", PlainText.of(TitleDisplay.decorate(ZIG, "Ziggfreed")));
    }

    @Test
    void aPlayerShowingNothingKeepsThePlainName() {
        assertNull(TitleDisplay.decorate(ZIG, "Ziggfreed"));
    }

    @Test
    void aTitleSwitchedOffOrNeverDefinedShowsNothingButStaysChosen() {
        ActiveTitles.put(ZIG, "pumpkin_king");
        assertNull(TitleDisplay.decorate(ZIG, "Ziggfreed"), "off means absent");

        ActiveTitles.put(ZIG, "never_defined");
        assertNull(TitleDisplay.decorate(ZIG, "Ziggfreed"));
        assertEquals("never_defined", ActiveTitles.of(ZIG), "the choice is kept for when the title comes back");
    }

    @Test
    void theSeamCarriesItToAMenuRowOnlineOrNot() {
        ActiveTitles.put(ZIG, "hallows_eve_hallowed");
        PlayerDisplayNames.fillDecorator(TitleDisplay.decorator());

        PlayerDisplayNames.fillLiveNames(id -> "Ziggfreed");
        assertEquals("Ziggfreed the Hallowed", row());

        PlayerDisplayNames.fillLiveNames(id -> null);
        assertEquals("Ziggfreed the Hallowed", row(),
                "an offline row shows the same title, so a title says nothing about presence");
    }

    @Test
    void anOnlineRowReadsTheLiveChoice() {
        PlayerDisplayNames.fillDecorator(TitleDisplay.decorator());
        PlayerDisplayNames.fillLiveNames(id -> "Ziggfreed");
        ZigTitleComponent titles = new ZigTitleComponent();
        TitleUnlocks.write(titles, ZIGGFREED, "Hallows_Eve_Hallowed", true);

        TitleUnlocks.activateWrite(titles, ZIGGFREED, "Hallows_Eve_Hallowed");
        assertEquals("Ziggfreed the Hallowed", row(), "shown the moment it is picked");

        TitleUnlocks.deactivateWrite(titles, ZIGGFREED);
        assertEquals("Ziggfreed", row(), "and gone the moment it is taken off");
    }

    @Tag("engine-items")
    @Test
    void anOfflineRowAfterARestartShowsTheTitleChosenBeforeIt() {
        ZigTitleComponent titles = new ZigTitleComponent();
        TitleUnlocks.write(titles, ZIGGFREED, "Hallows_Eve_Hallowed", true);
        TitleUnlocks.activateWrite(titles, ZIGGFREED, "Hallows_Eve_Hallowed");

        leaveAndRestart();

        assertEquals("Ziggfreed the Hallowed", row());
    }

    @Tag("engine-items")
    @Test
    void pickingClearingAndRevokingChangeWhatAnOfflineRowShows() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of(
                "Hallows_Eve_Hallowed", TitleAssetTest.title("{}", "Hallows_Eve_Hallowed"),
                "Hollow_Crown", TitleAssetTest.title("{ \"Text\": { \"TitleKey\": \"ev.crown\" } }", "Hollow_Crown")));
        LangCatalog.overrideForTests(Map.of(
                "ziggfreedcommon.title.display", "{0}, {1}",
                "hallowseve.title.hallows_eve_hallowed.display", "{0} the Hallowed",
                "hallowseve.ev.crown", "Gourd Monarch"));
        ZigTitleComponent titles = new ZigTitleComponent();
        TitleUnlocks.write(titles, ZIGGFREED, "Hallows_Eve_Hallowed", true);
        TitleUnlocks.write(titles, ZIGGFREED, "Hollow_Crown", true);

        TitleUnlocks.activateWrite(titles, ZIGGFREED, "Hallows_Eve_Hallowed");
        leaveAndRestart();
        assertEquals("Ziggfreed the Hallowed", row(), "picked");

        TitleUnlocks.activateWrite(titles, ZIGGFREED, "Hollow_Crown");
        leaveAndRestart();
        assertEquals("Ziggfreed, Gourd Monarch", row(), "picked another");

        TitleUnlocks.deactivateWrite(titles, ZIGGFREED);
        leaveAndRestart();
        assertEquals("Ziggfreed", row(), "cleared");

        TitleUnlocks.activateWrite(titles, ZIGGFREED, "Hollow_Crown");
        TitleUnlocks.write(titles, ZIGGFREED, "Hollow_Crown", false);
        leaveAndRestart();
        assertEquals("Ziggfreed", row(), "revoked, so no longer theirs to show");
    }

    @Tag("engine-items")
    @Test
    void anOfflineRowWithASwitchedOffOrUnknownTitleShowsNoneButKeepsTheChoice() {
        UUID other = UUID.randomUUID();
        ZigTitleComponent titles = new ZigTitleComponent();
        TitleUnlocks.write(titles, ZIGGFREED, "Pumpkin_King", true);
        TitleUnlocks.activateWrite(titles, ZIGGFREED, "Pumpkin_King");
        ActiveTitles.put(other, "never_defined");

        leaveAndRestart();

        assertEquals("Ziggfreed", row(), "off means absent, online or not");
        assertEquals("Someone", PlainText.of(PlayerDisplayNames.displayName(other, "Someone")));
        assertEquals("pumpkin_king", ActiveTitles.of(ZIG), "the choice is kept for when the title comes back");
    }

    @Test
    void aTitleWithNoLineOfItsOwnFollowsTheNameUnderTheNameItsFileGivesIt() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of("Hollow_Crown",
                TitleAssetTest.title("{ \"Text\": { \"TitleKey\": \"ev.crown\" } }", "Hollow_Crown")));
        LangCatalog.overrideForTests(Map.of(
                "ziggfreedcommon.title.display", "{0}, {1}",
                "hallowseve.ev.crown", "Gourd Monarch"));
        ActiveTitles.put(ZIG, "Hollow_Crown");

        assertEquals("Ziggfreed, Gourd Monarch", PlainText.of(TitleDisplay.decorate(ZIG, "Ziggfreed")),
                "the shown title's file names it, not its id spelled out");
    }

    private Path record() {
        return dir.resolve("shown-titles.json");
    }

    /** The player leaves (their choice is written down), the server restarts, and they stay away. */
    private void leaveAndRestart() {
        ActiveTitles.flush();
        ActiveTitles.clear();
        ActiveTitles.persistTo(record(), null);
        PlayerDisplayNames.fillLiveNames(id -> null);
        PlayerDisplayNames.fillDecorator(TitleDisplay.decorator());
    }

    @Nonnull
    private static String row() {
        return PlainText.of(PlayerDisplayNames.displayName(ZIG, "Ziggfreed"));
    }
}
