package com.ziggfreed.common.world;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The library's block rules for a world, written in its gameplay config's {@code Plugin} group under
 * {@code ZigBlockRules}, the way the engine's own plugins hang their per-world settings there. Under a
 * native {@code Parent} the child's {@code Plugin} group merges over the parent's, so a gameplay config
 * built on one that writes these rules keeps them.
 *
 * <p><b>{@code CreativeOnlyBreaking}.</b> The engine's {@code World.AllowBlockBreaking} is asked before the
 * breaker's game mode ({@code BreakBlockInteraction}), so false refuses a creative-mode player too, and
 * true lets anybody break. This rule is the missing middle: with {@code AllowBlockBreaking} true, only a
 * creative-mode player breaks a block; {@link BlockRuleSystems} refuses every other breaker (an adventure
 * player, an NPC, a projectile an adventure player or an NPC owns, an explosion) at the block-damage event,
 * while a creative player's own projectile still damages blocks (the event names its owner),
 * before the block takes any, and stops a block-group cycling tool outside creative. A creative break
 * never damages a block (it removes it outright), which is why the refusal sits on the damage and never
 * on the break. Placement and gathering keep their own {@code World} switches.
 */
public final class BlockRules {

    /** The key the rules are written under in a gameplay config's {@code Plugin} group. */
    public static final String ID = "ZigBlockRules";

    @Nullable
    private Boolean creativeOnlyBreaking;

    public static final BuilderCodec<BlockRules> CODEC = BuilderCodec.builder(BlockRules.class, BlockRules::new)
            .documentation("The library's block rules for every world that runs this gameplay config.")
            .appendInherited(new KeyedCodec<>("CreativeOnlyBreaking", Codec.BOOLEAN, false),
                    (o, v) -> o.creativeOnlyBreaking = v, o -> o.creativeOnlyBreaking,
                    (o, p) -> o.creativeOnlyBreaking = p.creativeOnlyBreaking)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("Whether only a creative-mode player may break a block here. Unauthored means false. "
                    + "Set it true together with World.AllowBlockBreaking true: the game refuses every break, "
                    + "creative ones included, while AllowBlockBreaking is false. Every other breaker (survival "
                    + "and adventure players, NPCs, explosions) is then refused before the block takes damage, "
                    + "and a block-group cycling tool works only in creative.").add()
            .build();

    public BlockRules() {
    }

    /** {@code CreativeOnlyBreaking}, unauthored false. */
    public boolean creativeOnlyBreaking() {
        return Boolean.TRUE.equals(creativeOnlyBreaking);
    }

    /** The rules a gameplay config writes, or null when it writes none (or there is no config). */
    @Nullable
    public static BlockRules of(@Nullable GameplayConfig config) {
        return config == null ? null : config.getPluginConfig().get(BlockRules.class);
    }

    /** Whether a world running {@code config} lets only a creative-mode player break a block. */
    public static boolean creativeOnlyBreaking(@Nullable GameplayConfig config) {
        BlockRules rules = of(config);
        return rules != null && rules.creativeOnlyBreaking();
    }

    /**
     * The decision the enforcing systems make: under {@code CreativeOnlyBreaking}, a breaker that is not a
     * creative-mode player is refused.
     *
     * @param creativeOnlyBreaking the world's rule
     * @param breakerMode          the breaker's game mode, or null when the breaker is no player (an NPC,
     *                             an explosion), which is never creative
     */
    public static boolean refusesBreaker(boolean creativeOnlyBreaking, @Nullable GameMode breakerMode) {
        return creativeOnlyBreaking && breakerMode != GameMode.Creative;
    }
}
