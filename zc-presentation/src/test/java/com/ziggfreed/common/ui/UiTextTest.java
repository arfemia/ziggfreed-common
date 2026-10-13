package com.ziggfreed.common.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.i18n.Msg;

/**
 * Only a bare translation goes to a {@code String} sink whole. The client resolves a {@code .Text}'s key but fills
 * no {@code {0}} and drops every child, so a translation carrying either has to be flattened first: sent whole, it
 * prints its placeholders (a party's member count read "{0}/{1}"), the third time params on {@code .Text} bit. The
 * predicate alone is under test; the builder {@link UiText#setText} writes into needs the engine's log manager.
 */
class UiTextTest {

    @Test
    void aBareTranslationIsResolvedByTheClient() {
        assertTrue(UiText.isClientResolvable(Msg.key("fixture.title")));
    }

    @Test
    void aTranslationWithAParamIsFlattened() {
        assertFalse(UiText.isClientResolvable(Msg.key("fixture.count", 1, 4)), "a scalar param");
        assertFalse(UiText.isClientResolvable(Msg.key("fixture.go_to", Msg.key("fixture.npc"))),
                "a nested message param");
    }

    @Test
    void aTranslationWithAChildIsFlattened() {
        assertFalse(UiText.isClientResolvable(Message.translation("fixture.title").insert(Msg.raw(" for now"))),
                "a translation with a child part");
        assertFalse(UiText.isClientResolvable(Msg.cat(Msg.key("fixture.title"), Msg.raw(" for now"))),
                "a Msg.cat composite");
    }

    @Test
    void rawTextIsFlattened() {
        assertFalse(UiText.isClientResolvable(Msg.raw("Shown")));
        assertFalse(UiText.isClientResolvable(null));
    }
}
