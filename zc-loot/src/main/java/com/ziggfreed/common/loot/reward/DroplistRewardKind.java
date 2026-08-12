package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.NativeLootService;
import com.ziggfreed.common.subject.Subject;

import org.joml.Vector3d;

/**
 * The reward kind that rolls a NATIVE Hytale drop list and spills the result on the ground:
 * {@code {"Kind": "droplist", "Params": {"Droplist": "Drop_Trork_Warrior"}}}.
 *
 * <p>It exists so a payout can reuse a loot table the game itself already balances. Every weighted
 * pool the engine ships - a mob's death drops, a chest's contents, a zone's encounter table - is an
 * {@code ItemDropList} asset, and any pack can author more of them or compose one out of others by
 * id. Naming one here means a reward inherits that whole table, its weights and its per-entry
 * quantity ranges, without any of it being restated in a second vocabulary.
 *
 * <table>
 *   <caption>Parameters</caption>
 *   <tr><th>Key</th><th>Meaning</th></tr>
 *   <tr><td>{@code Droplist}</td><td>The {@code ItemDropList} asset id to roll. Required; alias {@code Id}.</td></tr>
 *   <tr><td>{@code Rolls}</td><td>How many independent times to roll the WHOLE list, default 1.</td></tr>
 *   <tr><td>{@code Position}</td><td>Where the stacks land, {@code "x,y,z"}. Omit for the player's own feet.</td></tr>
 * </table>
 *
 * <h2>Ground drop, not inventory grant</h2>
 *
 * <p>The stacks are spawned as world item entities exactly the way a killed mob spills its loot, so
 * a full inventory is not a failure case here and the kind never needs the overflow sink the
 * {@code item} kinds use. That also makes it the right kind for a payout that belongs to a PLACE (a
 * corpse, a felled tree, an opened cache) rather than to a bag.
 *
 * <h2>Where the drop lands</h2>
 *
 * <p>{@code Position} is spawned at verbatim, in the world the receiving player is in. Omit it and
 * the stacks land one block above the player's own position, which is the same small lift the engine
 * gives a mob's death drops so items bounce on the floor instead of inside it. A caller that knows a
 * better spot than the player - the corpse it just credited, the block that was just broken - writes
 * one in at the moment it knows it, with {@code spec.with("Position", x + "," + y + "," + z)}.
 *
 * <h2>Rolls</h2>
 *
 * <p>{@code Rolls} repeats the whole roll, which is the engine's own reading of a repeat count for
 * this table: the built-in {@code /droplist <id> [count]} preview command loops the identical call
 * that many times. It is NOT a weight or a luck multiplier, and there is no engine knob for either.
 * For "this pool gives two to four items", author {@code RollsMin}/{@code RollsMax} on the list's own
 * {@code Choice} container, where the engine expresses it.
 *
 * <h2>Failure</h2>
 *
 * <p>No live player means no world to drop into, so the grant THROWS and the payout layer reports it
 * rather than dropping items nowhere. A reward that named no list at all throws the same way: reporting
 * it as paid would let a payout site charge its price and never learn the reward was empty. There is no
 * replay command: what a droplist produces is decided by a roll at the moment it pays out, so a "retry"
 * would hand over a different reward than the one that failed, and the engine's own {@code /droplist}
 * command only previews a roll in chat. An id no asset claims rolls empty and is warned once, never
 * fatal.
 */
public final class DroplistRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "droplist";

    /** Who this registration is attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /**
     * The ceiling on {@code Rolls}. A four-figure roll count is a typo or a runaway multiplier, and
     * either one spawns enough item entities to stall the world it lands in.
     */
    static final int MAX_ROLLS = 256;

    /** The lift applied to the player's own position when no {@code Position} is authored. */
    private static final double DEFAULT_DROP_LIFT = 1.0;

    private DroplistRewardKind() {
    }

    /** Register the droplist kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new DroplistRewardKind());
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        String dropListId = droplistIdOf(spec);
        if (dropListId == null) {
            throw new IllegalStateException(
                    "a '" + KIND + "' reward named no list - it needs a 'Droplist' parameter");
        }
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            throw new IllegalStateException("no live player to drop '" + dropListId + "' for");
        }
        Vector3d where = dropPosition(spec, ref);
        if (where == null) {
            throw new IllegalStateException("no position to drop '" + dropListId + "' at");
        }
        List<ItemStack> stacks = new ArrayList<>();
        int rolls = rollsOf(spec);
        for (int i = 0; i < rolls; i++) {
            stacks.addAll(NativeLootService.rollNative(dropListId));
        }
        NativeLootService.spawnInWorld(ref.getStore(), where, Rotation3f.IDENTITY, stacks);
    }

    // ==================== the parameter fold ====================

    /** The {@code ItemDropList} id to roll, or null when nothing usable was authored. */
    @Nullable
    static String droplistIdOf(@Nonnull RewardSpec spec) {
        String id = spec.paramOr("droplist", spec.paramOr("id", "")).trim();
        return id.isEmpty() ? null : id;
    }

    /** How many whole-list rolls to make: at least one, never more than {@link #MAX_ROLLS}. */
    static int rollsOf(@Nonnull RewardSpec spec) {
        long rolls = spec.longParam("rolls", 1L);
        if (rolls < 1L) {
            return 1;
        }
        return (int) Math.min(rolls, MAX_ROLLS);
    }

    /**
     * The authored {@code Position} as {@code {x, y, z}}, or null when unset or unreadable. A
     * malformed position reads as ABSENT rather than as the origin: dropping a reward at world zero
     * is worse than dropping it at the player's feet.
     */
    @Nullable
    static double[] authoredPositionOf(@Nonnull RewardSpec spec) {
        String written = spec.param("position");
        if (written == null || written.isBlank()) {
            return null;
        }
        String[] parts = written.split(",");
        if (parts.length != 3) {
            return null;
        }
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /** The authored position, else the receiving player's own position lifted one block. */
    @Nullable
    private static Vector3d dropPosition(@Nonnull RewardSpec spec, @Nonnull Ref<EntityStore> ref) {
        double[] authored = authoredPositionOf(spec);
        if (authored != null) {
            return new Vector3d(authored[0], authored[1], authored[2]);
        }
        TransformComponent transform = ref.getStore().getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        // A defensive copy: the engine hands back its live vector, and adding to it in place would
        // teleport whoever is standing on it.
        return new Vector3d(transform.getPosition()).add(0.0, DEFAULT_DROP_LIFT, 0.0);
    }

    /** Every parameter key this kind reads, for a validator that wants to warn about a typo. */
    @Nonnull
    public static Map<String, List<String>> parameterKeys() {
        return Map.of(KIND, List.of("droplist", "rolls", "position"));
    }
}
