package com.ziggfreed.common.loot.drops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockGathering;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.subject.Subject;

/**
 * Who a native drop list's roll is for, on the thread rolling it.
 *
 * <p>The engine rolls a drop list with no word of who caused it: {@code ItemDropContainer.populateDrops} is handed the
 * list it fills, a random source and the ids of the lists already rolled, and nothing else. So the moments that do
 * know say it here, on the world thread, just before the engine rolls, and a {@link ZigLootableDrop} asks at its roll:
 * <ul>
 *   <li>a break: every player break the block-break producer sees says {@link #breakingForNobody} first, and a break
 *       it credits then says {@link #breaking}, with the breaker and the block. The engine rolls the broken block's
 *       drops straight after its break event (its breaking, soft or harvest list, or the list of the tool that
 *       broke it), so the first roll after that has rolled one of the block's lists is the break's, and that one
 *       roll alone: the credit is spent there. A later roll of the same list (a block above falling, the
 *       second of a {@code Quantity} of rolls) rolls for nobody. A tool that only changes the block's state rather
 *       than breaking it fires no break event, so the drop list it rolls is not a break's and rolls for nobody. A
 *       credited break whose drops never roll (one a later handler cancels, one the engine drops nothing for, as in
 *       creative) keeps its credit until the next break the producer sees on this thread, {@link #clear}, or the
 *       first roll of one of its block's lists, which it answers;</li>
 *   <li>a death: {@link #dying}, for each entity the engine's death-drop pass may drop for this tick, in the pass's
 *       own order, with a probe that answers true once the engine has marked it dropped, its drop list, and its
 *       credited player or none. The engine marks one entity, then rolls its list, then the next, so the entity
 *       marked since the last roll asked, whose list this roll has rolled, is the one dropping now; should several
 *       have been marked since (rolls that reached no table), the last stamped of them. Every entity marked since
 *       is spent by that ask, whatever its list, so its credit never answers a later roll.</li>
 * </ul>
 * {@link #clear} forgets both once the death-drop pass has run, every tick. Sibling tables in one native roll (one set
 * of rolled lists) share one answer, and a table rolled inside a ZigLootable's own roll keeps that roll's answer
 * ({@link #within}).
 *
 * <p>With nobody known (most native rolls: an NPC's starting gear, a coop's produce, a stash, an objective's items)
 * the answer is null and the table rolls for nobody: a roll or entry whose {@code Requires} or {@code Conditions} ask
 * about a player refuses, and the rest pays. A block the breaker placed credits nobody, as the placed-block ledger
 * rules for XP and progress: a ZigLootable's ungated rows pay on it as the block's own vanilla entries do and its gated
 * rows refuse, so an owner keeps a row off placed blocks by gating it. Per thread, so each world's thread keeps its own.
 *
 * <p>Until a ZigLootable container has decoded ({@link #live}), what the moments say here returns at once and its
 * feeders do no work, so a server that authors none pays nothing on its breaks, kills and ticks.
 */
public final class DropContext {

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    /** Set once the first ZigLootable container decodes, and never cleared. */
    private static volatile boolean live;

    private DropContext() {
    }

    /**
     * Who a drop is for: the player its table's gates and chances are asked about (and its cues played to), and what
     * the moment was about, the entity that died or the block that broke, each when known.
     */
    public record Who(@Nonnull Subject subject, @Nonnull Ref<EntityStore> player, @Nullable Ref<EntityStore> target,
            @Nullable String blockId) {

        /** The factor context this drop's table reads: the player, the entity it happened to, the block it was. */
        @Nonnull
        public FactorContext context() {
            FactorContext about = FactorContext.about(player.isValid() ? player.getStore() : null, player, target);
            return blockId == null || blockId.isBlank() ? about : about.toBuilder().blockId(blockId).build();
        }
    }

    private static final class State {
        /** The last break's credit, until the one roll it answers; null for none. */
        @Nullable Who breaker;
        @Nonnull Set<String> breakerLists = Set.of();
        /** This tick's stamps, in stamp order. */
        final List<Dying> dying = new ArrayList<>();
        /** The next stamp's place in this tick's order. */
        int nextStamp;
        /** The engine's set of rolled lists the last answer was given for, held by identity. */
        @Nullable Set<String> lastRolled;
        @Nullable Who lastWho;
        int depth;
        @Nullable Who outer;
    }

    private static final class Dying {
        @Nonnull final BooleanSupplier dropped;
        @Nonnull final String list;
        @Nullable final Who who;
        /** Its place in this tick's stamp order. */
        final int stamp;
        /** Seen marked by an ask already: its roll has come, so it answers no later one. */
        boolean spent;

        Dying(@Nonnull BooleanSupplier dropped, @Nonnull String list, @Nullable Who who, int stamp) {
            this.dropped = dropped;
            this.list = list;
            this.who = who;
            this.stamp = stamp;
        }
    }

    // ==================== whether anything asks ====================

    /** Whether any ZigLootable container has decoded: until then nothing asks, so nobody need say anything. */
    public static boolean live() {
        return live;
    }

    /** A ZigLootable container decoded: from now on the moments say who drops are for. */
    static void markLive() {
        if (!live) {
            live = true;
        }
    }

    // ==================== what the moments say ====================

    /**
     * A player broke {@code block}, whose drops the engine rolls next: replaces this thread's last break, and answers
     * the first roll after it of one of the block's lists. Null {@code who} (a break nobody is credited with) clears
     * it.
     */
    public static void breaking(@Nullable Who who, @Nullable BlockType block) {
        if (!live) {
            return;
        }
        breaking(who, who == null ? Set.of() : dropListsOf(block));
    }

    /**
     * A break nobody is credited with (a block its breaker placed, a gather shape's further block, or one no moment
     * credits): forgets the last.
     */
    public static void breakingForNobody() {
        if (!live) {
            return;
        }
        breaking(null, Set.of());
    }

    /** {@link #breaking(Who, BlockType)} over the block's drop lists, lower-cased. */
    static void breaking(@Nullable Who who, @Nonnull Set<String> lists) {
        State state = STATE.get();
        boolean credited = who != null && !lists.isEmpty();
        state.breaker = credited ? who : null;
        state.breakerLists = credited ? lists : Set.of();
    }

    /**
     * An entity the engine's death-drop pass may drop for this tick, stamped in the pass's own order:
     * {@code dropped} answers true once the engine has marked it dropped, {@code dropListId} is the list that pass
     * rolls for it, and {@code who} is its credited player, or null for a death nobody is credited with. A blank
     * list is never rolled, so it is not noted.
     */
    public static void dying(@Nonnull BooleanSupplier dropped, @Nullable String dropListId, @Nullable Who who) {
        if (!live) {
            return;
        }
        String list = lowered(dropListId);
        if (list != null) {
            State state = STATE.get();
            state.dying.add(new Dying(dropped, list, who, state.nextStamp++));
        }
    }

    /** The death-drop pass has run: forget this thread's deaths and its last break. */
    public static void clear() {
        if (!live) {
            return;
        }
        State state = STATE.get();
        state.breaker = null;
        state.breakerLists = Set.of();
        state.dying.clear();
        state.nextStamp = 0;
        state.lastRolled = null;
        state.lastWho = null;
    }

    // ==================== what a roll asks ====================

    /**
     * Who the native roll carrying {@code rolled} (the engine's set of the lists that roll has rolled, one set per
     * roll) is for, or null for nobody. Inside a ZigLootable's own roll, that roll's answer. Otherwise the death
     * dropping now ({@link #dyingNow}), else the last break when this roll has rolled one of its block's lists,
     * which spends that break's credit.
     */
    @Nullable
    static Who whoFor(@Nonnull Set<String> rolled) {
        State state = STATE.get();
        if (state.depth > 0) {
            return state.outer;
        }
        if (state.lastRolled == rolled) {
            return state.lastWho;
        }
        Set<String> lists = loweredAll(rolled);
        Dying death = dyingNow(state, lists);
        Who who = null;
        if (death != null) {
            who = death.who;
        } else if (state.breaker != null && !Collections.disjoint(lists, state.breakerLists)) {
            who = state.breaker;
            state.breaker = null;
            state.breakerLists = Set.of();
        }
        state.lastRolled = rolled;
        state.lastWho = who;
        return who;
    }

    /**
     * The death this roll is: of the entities the pass has marked since the last ask, the last stamped whose list is
     * among {@code lists}, or null when none. Every entity marked since is spent here, so none answers a later roll.
     */
    @Nullable
    private static Dying dyingNow(@Nonnull State state, @Nonnull Set<String> lists) {
        Dying now = null;
        for (Dying dying : state.dying) {
            if (dying.spent || !dropped(dying)) {
                continue;
            }
            dying.spent = true;
            if (lists.contains(dying.list) && (now == null || dying.stamp > now.stamp)) {
                now = dying;
            }
        }
        return now;
    }

    /** Run {@code body} with {@code who} answering every roll nested inside it. */
    static void within(@Nullable Who who, @Nonnull Runnable body) {
        State state = STATE.get();
        Who saved = state.outer;
        state.depth++;
        state.outer = who;
        try {
            body.run();
        } finally {
            state.depth--;
            state.outer = saved;
        }
    }

    /** How many ZigLootable rolls this thread is inside right now. */
    static int depth() {
        return STATE.get().depth;
    }

    private static boolean dropped(@Nonnull Dying dying) {
        try {
            return dying.dropped.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code rolled}'s ids, trimmed and lower-cased. */
    @Nonnull
    private static Set<String> loweredAll(@Nonnull Set<String> rolled) {
        Set<String> out = new HashSet<>();
        for (String id : rolled) {
            add(out, id);
        }
        return out;
    }

    /**
     * Every drop list a player's break of {@code block} can roll, lower-cased: empty for none. Its physics list is
     * not one: only a break nobody made (a block left unsupported) rolls it.
     */
    @Nonnull
    static Set<String> dropListsOf(@Nullable BlockType block) {
        BlockGathering gathering = block == null ? null : block.getGathering();
        if (gathering == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        if (gathering.getBreaking() != null) {
            add(out, gathering.getBreaking().getDropListId());
        }
        if (gathering.getSoft() != null) {
            add(out, gathering.getSoft().getDropListId());
        }
        if (gathering.getHarvest() != null) {
            add(out, gathering.getHarvest().getDropListId());
        }
        Map<String, BlockGathering.BlockToolData> tools = gathering.getToolData();
        if (tools != null) {
            for (BlockGathering.BlockToolData tool : tools.values()) {
                if (tool != null) {
                    add(out, tool.getDropListId());
                }
            }
        }
        return out;
    }

    private static void add(@Nonnull Set<String> out, @Nullable String dropListId) {
        String id = lowered(dropListId);
        if (id != null) {
            out.add(id);
        }
    }

    /** {@code dropListId} trimmed and lower-cased, or null when blank. */
    @Nullable
    private static String lowered(@Nullable String dropListId) {
        return dropListId == null || dropListId.isBlank() ? null : dropListId.trim().toLowerCase(Locale.ROOT);
    }
}
