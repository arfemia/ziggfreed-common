package com.ziggfreed.common.loot.reward;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.i18n.ContentKeys;

/**
 * A reward that names the stack it hands over: {@code StackNameKey} is a lang key, resolved the way
 * every authored content key is, and {@code StackNameArg} fills its {@code {0}} as raw text. The name
 * is the engine's own per-stack display name, so every player's client reads it in their language
 * and the stack keeps it wherever it goes. A reward naming none hands over the stack untouched.
 *
 * <p>A replayed give (a reward parked for the player's next visit) carries no name: a console give
 * has no way to write one.
 */
public final class StackNames {

    /** The lang key naming the stack. */
    public static final String P_STACK_NAME_KEY = "StackNameKey";

    /** What fills that key's {0}. */
    public static final String P_STACK_NAME_ARG = "StackNameArg";

    private StackNames() {
    }

    /** {@code stack} carrying the name {@code spec} asks for, or {@code stack} itself when it asks none. */
    @Nonnull
    public static ItemStack stamp(@Nonnull ItemStack stack, @Nonnull RewardSpec spec) {
        String key = spec.param(P_STACK_NAME_KEY);
        if (key == null || key.isBlank()) {
            return stack;
        }
        String arg = spec.param(P_STACK_NAME_ARG);
        Message name = arg == null || arg.isBlank()
                ? ContentKeys.tr(key.trim())
                : ContentKeys.tr(key.trim(), arg.trim());
        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC, new ItemDisplayMetadata(name, null));
    }
}
