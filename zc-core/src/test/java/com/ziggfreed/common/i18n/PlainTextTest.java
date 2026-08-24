package com.ziggfreed.common.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

/**
 * The plain reading a {@code String}-only sink gets has to be the text the player would read, not
 * the key the author typed.
 *
 * <p>The failure this pins was shipped: an achievement icon's hover name flattened its title down
 * to {@code <mod>.achievement.<id>.title}, a search for the title matched nothing, and A-Z sorted
 * by key. The decision core is driven over an explicit catalogue (a plain map), because whether an
 * engine catalogue exists is an environment fact, not the rule under test; the no-catalogue degrade
 * is pinned separately through the public entry point, which is exactly the state a unit JVM is in.
 */
class PlainTextTest {

    private static final Function<String, String> CATALOGUE = Map.of(
            "fixture.go_to", "Go to {0}",
            "fixture.defeat", "Defeat {0} {1}",
            "fixture.npc.wren", "Ranger Wren",
            "ziggfreedcommon.fmt.cat", "{0}{1}")::get;

    @Test
    void rawTextAndChildrenPassThrough() {
        Message value = Msg.join(Msg.raw("First"), Msg.raw(" Steps"));

        assertEquals("First Steps", PlainText.render(value.getFormattedMessage(), CATALOGUE));
    }

    @Test
    void aTranslationResolvesToItsAuthoredValue() {
        Message value = Msg.key("fixture.npc.wren");

        assertEquals("Ranger Wren", PlainText.render(value.getFormattedMessage(), CATALOGUE));
    }

    /** The shipped bug: a nested-Message argument has to land in the slot, not vanish. */
    @Test
    void aMessageParamFillsItsSlot() {
        Message value = Msg.key("fixture.go_to", Msg.key("fixture.npc.wren"));

        assertEquals("Go to Ranger Wren", PlainText.render(value.getFormattedMessage(), CATALOGUE));
    }

    /** Counts and names together: a scalar as written, a nested name resolved. */
    @Test
    void scalarAndMessageParamsFillTogether() {
        Message value = Msg.key("fixture.defeat", 12, Msg.raw("Zombies"));

        assertEquals("Defeat 12 Zombies", PlainText.render(value.getFormattedMessage(), CATALOGUE));
    }

    /** A {@code Msg.cat} composite is nested fold nodes; the reading has to walk them whole. */
    @Test
    void aCatCompositeReadsWhole() {
        Message value = Msg.key("fixture.go_to",
                Msg.cat(Msg.key("fixture.npc.wren"), Msg.raw(" of the Wilds")));

        assertEquals("Go to Ranger Wren of the Wilds",
                PlainText.render(value.getFormattedMessage(), CATALOGUE));
    }

    /**
     * An id the catalogue does not carry contributes the id itself - the traceable degrade - and a
     * slot nothing bound stays literal, exactly as the client leaves it.
     */
    @Test
    void anUnknownKeyDegradesToTheKeyAndAnUnboundSlotStaysLiteral() {
        assertEquals("fixture.unshipped",
                PlainText.render(Msg.key("fixture.unshipped").getFormattedMessage(), CATALOGUE));
        assertEquals("Go to {0}",
                PlainText.render(Msg.key("fixture.go_to").getFormattedMessage(), CATALOGUE));
    }

    /** The public entry point in a JVM with no engine catalogue: the old degrade, never a throw. */
    @Test
    void noCatalogueDegradesEveryTranslationToItsId() {
        assertEquals("", PlainText.of(null));
        assertEquals("fixture.go_to x2",
                PlainText.of(Msg.join(Msg.key("fixture.go_to", Msg.raw("nowhere")),
                        Msg.raw(" x2"))));
    }
}
