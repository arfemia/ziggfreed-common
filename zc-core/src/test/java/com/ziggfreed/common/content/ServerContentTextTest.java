package com.ziggfreed.common.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The words an editor saves end up in a {@code .lang} file the engine's parser reads, whose grammar
 * cannot hold a backslash: one at the end of a line swallows the next entry, one before {@code n} or
 * {@code t} becomes a line break or a tab. So the guard refuses every backslash, and blank words.
 */
class ServerContentTextTest {

    @Test
    void wordsWithABackslashOrNothingInThemAreRefused() {
        assertNull(ServerContentText.words("ends in a backslash\\"));
        assertNull(ServerContentText.words("C:\\new folder"));
        assertNull(ServerContentText.words("   "));
        assertNull(ServerContentText.words(null));
    }

    @Test
    void ordinaryWordsPassWithTheirLineBreaksAndQuotes() {
        assertEquals("\"The Hunt\"\nPart two", ServerContentText.words("\"The Hunt\"\r\nPart two"));
    }
}
