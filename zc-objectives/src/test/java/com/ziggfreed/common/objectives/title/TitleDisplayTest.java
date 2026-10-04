package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.entity.title.ActiveTitles;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.PlainText;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;

/**
 * What the display seam shows once titles fill it: an offered title around the name, nothing for a
 * player showing none, nothing (but the choice kept) for a title switched off or never defined, and
 * the plain stored name for a player who has left. No entity store anywhere: the decorator reads the
 * mirror and the fold only, which is what lets a page on any world thread name any online player.
 */
class TitleDisplayTest {

    private static final UUID ZIG = UUID.randomUUID();

    @BeforeEach
    void titles() throws Exception {
        TitleConfig.getInstance().mergePackLayer(Map.of(
                "Hallows_Eve_Hallowed", TitleAssetTest.title("{}", "Hallows_Eve_Hallowed"),
                "Pumpkin_King", TitleAssetTest.title("{ \"Enabled\": false }", "Pumpkin_King")));
        LangCatalog.overrideForTests(Map.of(
                "ziggfreedcommon.title.display", "{0}, {1}",
                "hallowseve.title.hallows_eve_hallowed.display", "{0} the Hallowed"));
    }

    @AfterEach
    void clear() {
        ActiveTitles.clear();
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
    void theSeamCarriesItToAMenuRowAndAnOfflineRowReadsItsStoredName() {
        ActiveTitles.put(ZIG, "hallows_eve_hallowed");
        PlayerDisplayNames.fillLiveNames(id -> null);
        PlayerDisplayNames.fillDecorator(TitleDisplay.decorator());

        assertEquals("Ziggfreed the Hallowed", PlainText.of(PlayerDisplayNames.displayName(ZIG, "Ziggfreed")));

        ActiveTitles.evict(ZIG);
        assertEquals("Ziggfreed", PlainText.of(PlayerDisplayNames.displayName(ZIG, "Ziggfreed")));
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
}
