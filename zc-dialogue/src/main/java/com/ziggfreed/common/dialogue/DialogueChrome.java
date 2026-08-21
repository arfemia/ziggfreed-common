package com.ziggfreed.common.dialogue;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A conversation's own wording for the two lines the PAGE supplies rather than the author: the exit
 * row, and what a screen says when there is nothing to show.
 *
 * <p>The library ships wording for both, so a conversation that authors nothing here still reads
 * properly. This is for a character whose voice the default does not fit: a spirit says "The voice
 * fades" where a shopkeeper says "Farewell", and neither should have to be the wording every
 * conversation on the server gets.
 *
 * <pre>{@code
 * "Chrome": {
 *   "FarewellKey": "dialogue.grove_warden.farewell",
 *   "MissingKey":  "dialogue.grove_warden.missing"
 * }
 * }</pre>
 *
 * <p>Both are localization keys, resolved the way every other authored key on a conversation is: the
 * mod that ships the key lends its namespace, and a key nothing ships is shown as written. Leave a
 * leaf out and that one line stays the library's; the two are independent.
 */
public final class DialogueChrome {

    @Nullable String farewellKey;
    @Nullable String missingKey;

    public static final BuilderCodec<DialogueChrome> CODEC =
            BuilderCodec.builder(DialogueChrome.class, DialogueChrome::new)
                    .append(new KeyedCodec<>("FarewellKey", Codec.STRING, false),
                            (c, v) -> c.farewellKey = v, c -> c.farewellKey)
                    .documentation("Localization key for the exit row this conversation ends on. "
                            + "Unauthored uses the library's own wording.")
                    .add()
                    .append(new KeyedCodec<>("MissingKey", Codec.STRING, false),
                            (c, v) -> c.missingKey = v, c -> c.missingKey)
                    .documentation("Localization key for the line shown when a conversation has nothing "
                            + "to say (a missing file, a screen that resolved to nothing). Unauthored "
                            + "uses the library's own wording.")
                    .add()
                    .build();

    public DialogueChrome() {
    }

    /** The authored exit-row key, or null to take the library's wording. */
    @Nullable
    public String getFarewellKey() {
        return farewellKey;
    }

    /** The authored nothing-to-say key, or null to take the library's wording. */
    @Nullable
    public String getMissingKey() {
        return missingKey;
    }
}
