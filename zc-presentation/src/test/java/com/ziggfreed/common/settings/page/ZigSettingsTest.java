package com.ziggfreed.common.settings.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

/**
 * What the Settings tab lists and in what order: the consumer's sections first, then the library's three
 * in their fixed order, each filled by the module that owns it; an unfilled slot draws nothing; a
 * supplier that throws or answers nothing costs only its own section; the consumer seam is one call,
 * last write wins.
 */
class ZigSettingsTest {

    @BeforeEach
    @AfterEach
    void clear() {
        ZigSettings.clearForTests();
    }

    @Nonnull
    static SettingsSection section(@Nonnull String id) {
        return new SettingsSection(id, Message.raw(id),
                List.of(SettingsRow.heading(id + ".heading", Message.raw(id), viewer -> true)));
    }

    @Nonnull
    private static List<String> ids(@Nonnull List<SettingsSection> sections) {
        return sections.stream().map(SettingsSection::id).toList();
    }

    @Test
    void theConsumersSectionsComeFirstThenTheLibrarysInTheirFixedOrder() {
        ZigSettings.fill(SettingsSlot.TITLE, () -> section("title"));
        ZigSettings.fill(SettingsSlot.QUEST_TRACKER, () -> section("tracker"));
        ZigSettings.fill(SettingsSlot.NOTIFICATIONS, () -> section("notifications"));
        ZigSettings.consumer(() -> List.of(section("mmo.a"), section("mmo.b")));

        assertEquals(List.of("mmo.a", "mmo.b", "tracker", "notifications", "title"), ids(ZigSettings.sections()));
    }

    @Test
    void anEmptySeamIsTheLibrarysSectionsAloneAndAnUnfilledSlotDrawsNothing() {
        assertTrue(ZigSettings.sections().isEmpty());
        ZigSettings.fill(SettingsSlot.NOTIFICATIONS, () -> section("notifications"));

        assertEquals(List.of("notifications"), ids(ZigSettings.sections()));
    }

    @Test
    void theLastConsumerWinsAndNullEmptiesTheSeam() {
        ZigSettings.consumer(() -> List.of(section("first")));
        ZigSettings.consumer(() -> List.of(section("second")));
        assertEquals(List.of("second"), ids(ZigSettings.sections()));

        ZigSettings.consumer(null);
        assertTrue(ZigSettings.sections().isEmpty());
    }

    @Test
    void aSupplierThatThrowsOrAnswersNothingCostsOnlyItsOwnSection() {
        ZigSettings.fill(SettingsSlot.QUEST_TRACKER, () -> {
            throw new IllegalStateException("boom");
        });
        ZigSettings.fill(SettingsSlot.NOTIFICATIONS, () -> null);
        ZigSettings.fill(SettingsSlot.TITLE, () -> section("title"));
        ZigSettings.consumer(() -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(List.of("title"), ids(ZigSettings.sections()));
    }

    @Test
    void theSettingsDestinationIsDeclaredUnderItsOneWord() {
        assertEquals("Settings", SettingsDestinations.TYPE);
        assertEquals("ziggfreedcommon", SettingsDestinations.OWNER);
        assertInstanceOf(SettingsDestinations.Settings.class, SettingsDestinations.SETTINGS);
    }
}
