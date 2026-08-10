package com.ziggfreed.common.dialogue;

import java.util.Set;

import javax.annotation.Nonnull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Option-level authoring shorthand: one flat sugar key (e.g. {@code "Goto":"next"})
 * an author writes on an option instead of the verbose canonical {@code Actions}
 * entry. Registered alongside an action type; {@link DialogueSugar} runs every
 * registered expander as a pre-codec Gson rewrite, appending canonical actions and
 * stripping the sugar key, so nothing downstream needs a sugar branch.
 *
 * <p>Bare keys expand in {@link #order} (low first); the {@code Do} array is the
 * explicit-order escape hatch handled by {@link DialogueSugar} itself.
 */
public interface DialogueSugarExpander {

    /** The PascalCase sugar key this expander consumes (e.g. {@code "Goto"}, {@code "Accept"}). */
    @Nonnull String key();

    /** Fixed bare-key expansion order; lower runs first. */
    int order();

    /**
     * Append the canonical action object(s) for {@code value} (the sugar key's
     * value) to {@code actionsOut}. {@code option} is the whole option object (so
     * an expander may read a sibling modifier key, e.g. a Reward's {@code Once}).
     * No-op when the value type does not match (the validator/codec surfaces it).
     */
    void expand(@Nonnull JsonElement value, @Nonnull JsonObject option, @Nonnull JsonArray actionsOut);

    /**
     * Every option key this expander consumes (stripped after desugaring). Defaults
     * to just {@link #key()}; override when the expander also reads a sibling
     * modifier key (e.g. a Reward expander consuming {@code Once}).
     */
    @Nonnull
    default Set<String> consumedKeys() {
        return Set.of(key());
    }
}
