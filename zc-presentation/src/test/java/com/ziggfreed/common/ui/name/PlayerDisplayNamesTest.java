package com.ziggfreed.common.ui.name;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

/**
 * The one way a menu names a player: the plain-name ladder (live, stored, the start of the id), the
 * decorator a higher module fills, and the guard that keeps a failing decorator from costing a row
 * its name.
 */
class PlayerDisplayNamesTest {

    private static final UUID ZIG = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");

    private final Map<UUID, String> online = new HashMap<>();

    @BeforeEach
    void setUp() {
        PlayerDisplayNames.fillLiveNames(online::get);
    }

    @AfterEach
    void tearDown() {
        PlayerDisplayNames.fillLiveNames(null);
        PlayerDisplayNames.fillDecorator(null);
    }

    @Test
    void aLiveUsernameWinsOverTheStoredOne() {
        online.put(ZIG, "Ziggfreed");
        assertEquals("Ziggfreed", PlayerDisplayNames.plainName(ZIG, "OldName"), "a rename shows at once");
    }

    @Test
    void anOfflinePlayerReadsTheStoredNameElseTheStartOfTheirId() {
        assertEquals("OldName", PlayerDisplayNames.plainName(ZIG, "OldName"));
        assertEquals("0f1e2d3c", PlayerDisplayNames.plainName(ZIG, "  "));
        assertEquals("0f1e2d3c", PlayerDisplayNames.plainName(ZIG, null));
    }

    @Test
    void withNothingFilledANameIsItsPlainText() {
        online.put(ZIG, "Ziggfreed");

        Message name = PlayerDisplayNames.displayName(ZIG, null);

        assertEquals("Ziggfreed", name.getRawText());
        assertNull(name.getMessageId());
    }

    @Test
    void aFilledDecoratorIsAskedWithThePlainNameAndItsAnswerShows() {
        online.put(ZIG, "Ziggfreed");
        List<String> asked = new ArrayList<>();
        PlayerDisplayNames.fillDecorator((id, plain) -> {
            asked.add(id + "=" + plain);
            return Message.translation("test.title.display");
        });

        assertEquals("test.title.display", PlayerDisplayNames.displayName(ZIG, "OldName").getMessageId());
        assertEquals(List.of(ZIG + "=Ziggfreed"), asked);
    }

    @Test
    void aDecoratorWithNoAnswerLeavesThePlainName() {
        PlayerDisplayNames.fillDecorator((id, plain) -> null);
        assertEquals("OldName", PlayerDisplayNames.displayName(ZIG, "OldName").getRawText());
    }

    @Test
    void aThrowingDecoratorNeverCostsTheRowItsName() {
        PlayerDisplayNames.fillDecorator((id, plain) -> {
            throw new IllegalStateException("boom");
        });

        assertEquals("OldName", PlayerDisplayNames.displayName(ZIG, "OldName").getRawText());
        assertEquals("OldName", PlayerDisplayNames.displayName(ZIG, "OldName").getRawText(), "and again");
    }

    @Test
    void aThrowingLiveLookupFallsBackToTheStoredName() {
        PlayerDisplayNames.fillLiveNames(id -> {
            throw new IllegalStateException("no universe here");
        });
        assertEquals("OldName", PlayerDisplayNames.plainName(ZIG, "OldName"));
    }
}
