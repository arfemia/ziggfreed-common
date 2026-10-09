package com.ziggfreed.common.objectives.waypoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.objectives.indicator.QuestIndicators;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.world.WorldSelector;
import com.ziggfreed.common.worldmap.GatewayAsset;
import com.ziggfreed.common.worldmap.Gateways;
import com.ziggfreed.common.worldmap.WaypointTarget;

/**
 * Where a viewer is pointed, decided on the world thread from the world they stand in: each tracked
 * quest's destination, and each character with a quest on offer. Pure over what it is handed, so a
 * test drives it with a real engine and no server.
 */
public final class QuestWaypointTargets {

    /** A position key naming a gateway rather than a character. Never contains {@code :}, which marker ids reserve. */
    public static final String GATEWAY_PREFIX = "gateway/";

    private QuestWaypointTargets() {
    }

    /**
     * The character a step routes the player to: its first hand-in locked to one place, else its
     * first place-targeted objective naming one. Null when the step names no place.
     */
    @Nullable
    public static String destinationOf(@Nonnull List<ObjectiveDef> step, @Nonnull Predicate<String> placeTargeted) {
        for (ObjectiveDef objective : step) {
            if (QuestObjectiveAsset.HAND_IN_KIND.equalsIgnoreCase(objective.kind().trim())) {
                String lock = objective.turnInLockId();
                if (lock != null && !lock.isBlank()) {
                    return lock.trim();
                }
            } else if (placeTargeted.test(objective.kind())) {
                String target = objective.target();
                if (target != null && !target.isBlank()) {
                    return target.trim();
                }
            }
        }
        return null;
    }

    /**
     * One target per place the viewer's tracked quests send them, in tracking order, drawn with
     * {@code icon} (null for the service default). A character standing under a {@code Where} this
     * world matches, or one nothing places, is the target itself; one standing only in other worlds
     * is reached through each gateway here leading into one of them ({@link #GATEWAY_PREFIX} plus its
     * id, shared by every quest it serves); with none, nothing.
     */
    @Nonnull
    public static List<WaypointTarget> plan(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nullable String worldName, @Nullable String worldGameplayConfig,
            @Nonnull Predicate<String> placeTargeted,
            @Nonnull Function<String, List<WorldSelector>> wheresOf,
            @Nonnull Collection<GatewayAsset> gateways,
            @Nonnull Function<Quest, Message> titleOf,
            @Nullable String icon) {
        List<WaypointTarget> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Quest quest : engine.trackedActive(subject)) {
            String npc = destinationOf(engine.activeStepObjectives(subject, quest), placeTargeted);
            if (npc == null) {
                continue;
            }
            List<WorldSelector> wheres = wheresOf.apply(npc);
            if (wheres.isEmpty() || standsHere(wheres, worldName, worldGameplayConfig)) {
                if (seen.add(npc.toLowerCase(Locale.ROOT))) {
                    out.add(new WaypointTarget(npc, npc, titleOf.apply(quest), icon));
                }
                continue;
            }
            for (GatewayAsset gateway : Gateways.leadingInto(gateways, wheres, worldName, worldGameplayConfig)) {
                String key = GATEWAY_PREFIX + gateway.getId().toLowerCase(Locale.ROOT);
                if (seen.add(key)) {
                    out.add(new WaypointTarget(key, key, titleOf.apply(quest), icon));
                }
            }
        }
        return out;
    }

    /**
     * One target per character the map marks for a quest on offer, titled through {@code titleOf} and
     * drawn with its situation's own map icon. A mark never routes through a gateway: it is for a
     * character in the viewer's own world.
     */
    @Nonnull
    public static List<WaypointTarget> available(@Nonnull List<QuestIndicators.MapMark> marks,
            @Nonnull Function<Quest, Message> titleOf) {
        List<WaypointTarget> out = new ArrayList<>();
        for (QuestIndicators.MapMark mark : marks) {
            out.add(new WaypointTarget(mark.npcId(), mark.npcId(), titleOf.apply(mark.reading().quest()),
                    mark.reading().knob().mapIcon()));
        }
        return out;
    }

    /** What the library still draws while a consumer draws its own marks: only the pointers through a gateway. */
    @Nonnull
    public static List<WaypointTarget> whileConsumerDraws(@Nonnull List<WaypointTarget> targets) {
        List<WaypointTarget> out = new ArrayList<>();
        for (WaypointTarget target : targets) {
            if (isGatewayKey(target.positionKey())) {
                out.add(target);
            }
        }
        return out;
    }

    /** Does {@code positionKey} name a gateway rather than a character? */
    static boolean isGatewayKey(@Nonnull String positionKey) {
        return positionKey.startsWith(GATEWAY_PREFIX);
    }

    private static boolean standsHere(@Nonnull List<WorldSelector> wheres, @Nullable String worldName,
            @Nullable String worldGameplayConfig) {
        for (WorldSelector where : wheres) {
            if (where.match(worldName, worldGameplayConfig) != null) {
                return true;
            }
        }
        return false;
    }
}
