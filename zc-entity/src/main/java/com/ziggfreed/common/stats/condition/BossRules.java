package com.ziggfreed.common.stats.condition;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.common.damage.onhit.ShrugWords;

/**
 * The one answer to who is a boss or an elite and whether a kind of crowd control lands on a foe now, so every site
 * that holds a foe back asks the same place and a fight changes the answer by putting a tagged effect on its boss.
 * Needs nothing wired: a server running CommonLib alone holds it.
 *
 * <p><b>Who.</b> A creature in the library's NPC group {@value #FAMILY_GROUP}, or in any NPC group whose own native
 * {@code Tags} carry {@value #RANK_TAG} {@code Boss} or {@code Elite} (a mod opts its boss in from its own group
 * file).
 *
 * <p><b>What lands.</b> Four layers each name kinds ({@link ControlKind}): from anyone, a talent's included, or from
 * nothing, the game's own controls included; a kind a layer does not name falls to the one below, and inside a layer
 * shrugs beats lands ({@link ControlReading#resolve}): a timed rule effect the foe wears (a window), an infinite one (a
 * phase's standing rule), the tags on its own NPC groups (a boss's own default), each naming kinds under
 * {@value #LANDS_TAG} or {@value #SHRUGS_TAG}, and for a boss or an elite each kind's own {@code OnBossOrElite} (the
 * family default, {@link ControlKinds#familyRule}, on the Control Kinds screen). Below them a boss or an elite reads
 * {@link ControlReading#USUAL} (an ability's own control lands at the cast's own strength, a talent's does not) and
 * anything else {@link ControlReading#LANDS}. Every read is live.
 *
 * <pre>{@code
 * // a boss's own group, opting in and shrugging taunts
 * { "IncludeRoles": [ "My_Boss*" ], "Tags": { "ZigRank": [ "Boss" ], "ZigControlShrugs": [ "Taunt" ] } }
 * // a window effect a fight puts on its boss
 * { "Duration": 6.0, "Tags": { "ZigControlLands": [ "Stun", "Interrupt" ] } }
 * }</pre>
 *
 * <p>World thread. Every call answers, never throws: a read that fails answers the structural reading.
 */
public final class BossRules {

    /** The native {@code Tags} key naming the kinds that land from anyone. */
    public static final String LANDS_TAG = "ZigControlLands";

    /** The native {@code Tags} key naming the kinds that land from nothing. */
    public static final String SHRUGS_TAG = "ZigControlShrugs";

    /** The native {@code Tags} key an NPC group counts its roles as bosses or elites with ({@code Boss}, {@code Elite}). */
    public static final String RANK_TAG = "ZigRank";

    /**
     * The library's NPC group of every boss and elite it counts (the game's own, by exact role id: it includes
     * {@code Zig_Bosses} and {@code Zig_Elites}, each tagged {@value #RANK_TAG}), which every foe family excludes.
     */
    public static final String FAMILY_GROUP = ZigFoeFamilyCondition.BOSS_OR_ELITE_GROUP;

    private BossRules() {
    }

    /** One foe's layers, read once for several kinds. */
    private record Layers(boolean bossOrElite, @Nonnull ControlRule window, @Nonnull ControlRule standing,
            @Nonnull ControlRule own, @Nonnull ControlRule family) {

        @Nonnull
        ControlReading reading(@Nullable ControlKind kind) {
            return ControlReading.resolve(kind, bossOrElite, window, standing, own, family);
        }

        /** Does a rule effect worn (a window, else a phase's) answer {@code kind} with LANDS? */
        boolean effectOpens(@Nonnull ControlKind kind) {
            ControlReading answer = window.answer(kind);
            if (answer == null) {
                answer = standing.answer(kind);
            }
            return answer == ControlReading.LANDS;
        }
    }

    /**
     * Is {@code foe} a boss or an elite: a creature in {@value #FAMILY_GROUP} or in a group tagged {@value #RANK_TAG}?
     * A player never is. False when it cannot tell.
     */
    public static boolean isBossOrElite(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> foe) {
        try {
            int role = roleOf(accessor, foe);
            return role >= 0 && bossOrEliteRole(role, ControlRuleTables.groups());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * What {@code foe}'s rules say of {@code kind} now. A null kind (a control of no kind, an effect in no family that
     * names one) reads structural alone.
     */
    @Nonnull
    public static ControlReading reading(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> foe, @Nullable ControlKind kind) {
        try {
            if (kind == null || !kind.in(namedMask())) {
                return ControlReading.resolve(null, isBossOrElite(accessor, foe), null, null, null, null);
            }
            return layers(accessor, foe).reading(kind);
        } catch (Throwable t) {
            return ControlReading.resolve(null, isBossOrElite(accessor, foe), null, null, null, null);
        }
    }

    /**
     * Does a control of {@code kind} land on {@code foe} now, {@code byTalent} saying a talent or a proc added it to
     * the move (an ability's own reads false)?
     */
    public static boolean lands(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            @Nullable ControlKind kind, boolean byTalent) {
        return lands(accessor, foe, kind, byTalent, null);
    }

    /**
     * {@link #lands(ComponentAccessor, Ref, ControlKind, boolean)}, naming who cast it: a refusal is that caster's to
     * be told of. A null {@code caster} tells no one.
     */
    public static boolean lands(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            @Nullable ControlKind kind, boolean byTalent, @Nullable Ref<EntityStore> caster) {
        ControlReading reading = reading(accessor, foe, kind);
        if (reading.lands(byTalent)) {
            return true;
        }
        refused(accessor, foe, kind, reading, caster);
        return false;
    }

    /**
     * Does the effect at {@code effectIndex} land on {@code foe} now as a control: every kind its effect families name
     * ({@link EffectFamilies#controlKinds}) must land, and an effect of no kind reads structural alone.
     */
    public static boolean landsEffect(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            int effectIndex, boolean byTalent, @Nullable Ref<EntityStore> caster) {
        int kinds = EffectFamilies.controlKindMask(effectIndex);
        if (kinds == 0 || (kinds & namedMask()) == 0) {
            ControlKind only = Integer.bitCount(kinds) == 1 ? ControlKind.ofMask(kinds).iterator().next() : null;
            return lands(accessor, foe, only, byTalent, caster);
        }
        Layers layers;
        try {
            layers = layers(accessor, foe);
        } catch (Throwable t) {
            return lands(accessor, foe, null, byTalent, caster);
        }
        for (ControlKind kind : ControlKind.ofMask(kinds)) {
            ControlReading reading = layers.reading(kind);
            if (!reading.lands(byTalent)) {
                refused(accessor, foe, kind, reading, caster);
                return false;
            }
        }
        return true;
    }

    /**
     * Every kind {@code foe}'s rules shrug now, as a mask of {@link ControlKind} bits; 0 at once when no loaded rule
     * shrugs anything ({@link #mayShrugMask}).
     */
    public static int shruggedMask(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe) {
        int possible = mayShrugMask();
        if (possible == 0) {
            return 0;
        }
        try {
            Layers layers = layers(accessor, foe);
            int out = 0;
            for (ControlKind kind : ControlKind.ofMask(possible)) {
                if (layers.reading(kind) == ControlReading.SHRUGS) {
                    out |= kind.bit();
                }
            }
            return out;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The kinds some loaded rule (a group's tags or an effect's) shrugs, as a mask: a kind outside it never reads
     * {@link ControlReading#SHRUGS} on anyone, so a per-tick reader skips it at no cost.
     */
    public static int mayShrugMask() {
        return ControlRuleTables.groups().shrugs() | ControlRuleTables.effects().shrugs()
                | ControlKinds.familyRule().shrugs();
    }

    /** May some foe shrug {@code kind} ({@link #mayShrugMask})? */
    public static boolean mayShrug(@Nonnull ControlKind kind) {
        return kind.in(mayShrugMask());
    }

    /**
     * Do {@code foe}'s rules shrug {@code kind} now, from anyone, the game's own and a fight's own included (a stun, a
     * knockback, a taunt that must not land at all)? Free while no loaded rule shrugs it ({@link #mayShrug}); false
     * whenever it cannot tell.
     */
    public static boolean shrugs(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            @Nonnull ControlKind kind) {
        try {
            return mayShrug(kind) && reading(accessor, foe, kind) == ControlReading.SHRUGS;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@link #shrugs(ComponentAccessor, Ref, ControlKind)}, telling {@code caster} when it does (the shrug word, as
     * every refusal with a caster is told).
     */
    public static boolean shrugs(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            @Nonnull ControlKind kind, @Nullable Ref<EntityStore> caster) {
        if (!shrugs(accessor, foe, kind)) {
            return false;
        }
        refused(accessor, foe, kind, ControlReading.SHRUGS, caster);
        return true;
    }

    /**
     * Tell {@code caster} that {@code foe} shrugged their control of {@code kind}, for a site that found the shrug
     * itself (an effect taken off the tick it landed, a pin let go when a phase came to shrug it): the word every
     * refusal with a caster shows.
     */
    public static void shrugged(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            @Nullable ControlKind kind, @Nullable Ref<EntityStore> caster) {
        refused(accessor, foe, kind, ControlReading.SHRUGS, caster);
    }

    /**
     * Does a rule effect {@code foe} wears now (a window, else a phase's standing rule) say every kind in
     * {@code kindMask} lands from anyone? A stun immunity of the foe's own ({@code ZigStunImmune}, zc-effects'
     * {@code StunControl.isImmune}) gives way to it for those kinds, so a fight's window opens what the boss's
     * immunity closes; its groups' tags and the family default never do. False for an empty mask, for a kind no worn
     * rule effect names, and whenever it cannot tell; free while no loaded effect names a kind.
     */
    public static boolean opened(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            int kindMask) {
        try {
            if (kindMask == 0 || (kindMask & ControlRuleTables.effects().named()) != kindMask) {
                return false;
            }
            Layers layers = layers(accessor, foe);
            for (ControlKind kind : ControlKind.ofMask(kindMask)) {
                if (!layers.effectOpens(kind)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The rule the effect at {@code effectIndex} carries in its own tags, or null for an effect that is no rule effect
     * (a fight taking its boss's standing rules off reads it).
     */
    @Nullable
    public static ControlRule ruleOf(int effectIndex) {
        return ControlRuleTables.effects().at(effectIndex);
    }

    /** Every kind some loaded rule names at all; a kind outside it reads structural on everyone. */
    private static int namedMask() {
        return ControlRuleTables.groups().named() | ControlRuleTables.effects().named()
                | ControlKinds.familyRule().named();
    }

    /**
     * Where a refusal with a caster is told to that caster: a player sees the shrug word over the foe
     * ({@link ShrugWords}: every shrug, a boss's own rule or the family default refusing a talent's control alike, at
     * most once every few seconds per player, foe and kind, behind the crit word's Settings switch). A null
     * {@code caster} tells no one.
     */
    private static void refused(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe,
            @Nullable ControlKind kind, @Nonnull ControlReading reading, @Nullable Ref<EntityStore> caster) {
        if (caster != null) {
            ShrugWords.show(accessor, caster, foe, kind);
        }
    }

    @Nonnull
    private static Layers layers(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe) {
        ControlRuleTables.Groups groups = ControlRuleTables.groups();
        ControlRuleTables.Effects effects = ControlRuleTables.effects();
        int role = roleOf(accessor, foe);
        boolean bossOrElite = role >= 0 && bossOrEliteRole(role, groups);
        ControlRule own = ControlRule.NONE;
        if (role >= 0) {
            int[] tagged = groups.tagged();
            for (int i = 0; i < tagged.length; i++) {
                if (ZigFoeFamilyCondition.inSet(tagged[i], role)) {
                    own = own.with(groups.taggedRules()[i]);
                }
            }
        }
        ControlRule window = ControlRule.NONE;
        ControlRule standing = ControlRule.NONE;
        if (effects.any() && foe.isValid()) {
            EffectControllerComponent controller = accessor.getComponent(foe,
                    EffectControllerComponent.getComponentType());
            if (controller != null && !controller.getActiveEffects().isEmpty()) {
                for (ActiveEntityEffect active : controller.getActiveEffects().values()) {
                    ControlRule rule = active == null ? null : effects.at(active.getEntityEffectIndex());
                    if (rule == null) {
                        continue;
                    }
                    if (active.isInfinite()) {
                        standing = standing.with(rule);
                    } else {
                        window = window.with(rule);
                    }
                }
            }
        }
        return new Layers(bossOrElite, window, standing, own, ControlKinds.familyRule());
    }

    /** Is the role in the family group (looked up live, as every group ask is) or in a ranked group? */
    private static boolean bossOrEliteRole(int role, @Nonnull ControlRuleTables.Groups groups) {
        if (ZigFoeFamilyCondition.inSet(NPCGroup.getAssetMap().getIndex(FAMILY_GROUP), role)) {
            return true;
        }
        for (int ranked : groups.ranked()) {
            if (ZigFoeFamilyCondition.inSet(ranked, role)) {
                return true;
            }
        }
        return false;
    }

    /** The creature's role index, or -1 for an entity that is no creature (a player). */
    private static int roleOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> foe) {
        if (!foe.isValid()) {
            return -1;
        }
        NPCEntity npc = accessor.getComponent(foe, NPCEntity.getComponentType());
        return npc == null ? -1 : npc.getNPCTypeIndex();
    }
}
