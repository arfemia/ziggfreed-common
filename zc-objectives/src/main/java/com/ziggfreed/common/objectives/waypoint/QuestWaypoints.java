package com.ziggfreed.common.objectives.waypoint;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.npc.placement.runtime.PlacementWorlds;
import com.ziggfreed.common.objectives.indicator.QuestIndicatorConfig;
import com.ziggfreed.common.objectives.indicator.QuestIndicators;
import com.ziggfreed.common.objectives.indicator.QuestMarkYield;
import com.ziggfreed.common.objectives.marker.QuestMarkerListener;
import com.ziggfreed.common.objectives.marker.QuestMarkerScope;
import com.ziggfreed.common.objectives.marker.QuestMarkers;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.worldmap.Gateways;
import com.ziggfreed.common.worldmap.WaypointService;
import com.ziggfreed.common.worldmap.WaypointTarget;

/**
 * The library's quest marks on the compass and map: a marker at the place each tracked quest's
 * current step sends the player, or at the nearest way into the world that place stands in; and one
 * at each character in this world with a quest the player could take. Always on.
 *
 * <p>A surface of {@link QuestMarkers}, so it refreshes on the quest engine's six events, at player
 * ready and on the slow sweep, never a tick. The nearest gateway still follows the player, because
 * the resolver reads where they stand on every map update.
 *
 * <p>While a consumer still draws its own quest marks ({@link QuestMarkYield}) only the pointers
 * through a gateway stay, which such a consumer never draws, so nothing is drawn twice.
 */
public final class QuestWaypoints implements QuestMarkerListener {

    /** The provider key, and the prefix of every marker id. */
    public static final String PROVIDER_KEY = "ziggfreedcommon:quest_markers";

    /** The hover line on a character with a quest on offer; {0} is the quest's title. */
    public static final String AVAILABLE_TITLE_KEY = "ziggfreedcommon.progression.quest.marker.available";

    static final QuestWaypoints INSTANCE = new QuestWaypoints();

    private static final ConcurrentHashMap<UUID, List<WaypointTarget>> TRACKED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, List<WaypointTarget>> AVAILABLE = new ConcurrentHashMap<>();

    /**
     * Two sources in precedence order: tracked first, so a character both point at keeps the
     * tracker's marker (a target id is kept for the first source naming it, and both use the
     * character's id). {@code ignoreViewDistance} stays on: a pointer points at what the player
     * usually cannot see yet.
     */
    private static final WaypointService WAYPOINTS = WaypointService.builder(PROVIDER_KEY)
            .positionResolver(new QuestWaypointResolver())
            .build();

    static {
        WAYPOINTS.addSource(viewerId -> TRACKED.getOrDefault(viewerId, List.of()));
        WAYPOINTS.addSource(viewerId -> AVAILABLE.getOrDefault(viewerId, List.of()));
    }

    private QuestWaypoints() {
    }

    /**
     * Add this surface to the quest-mark hub. Call once from setup, after {@link QuestMarkers#install}.
     * A removed world's gateway index is dropped by {@link Gateways} itself.
     */
    public static void install(@Nonnull PluginBase plugin) {
        try {
            QuestMarkers.addListener("waypoints", INSTANCE);
            SafeLog.info("[progression] quest waypoints installed (" + PROVIDER_KEY + "): every tracked quest"
                    + " is pointed at, through the nearest gateway when its character stands in another world,"
                    + " and the quests on offer are marked on the map");
        } catch (Throwable t) {
            SafeLog.warn("[progression] the quest waypoints could not be installed; no quest is marked on the"
                    + " compass this boot", t);
        }
    }

    @Override
    public void evaluate(@Nonnull QuestMarkerScope scope) {
        World world = scope.world();
        WAYPOINTS.registerForWorld(world);
        QuestEngine engine = ProgressionRuntime.quests();
        List<WaypointTarget> tracked = QuestWaypointTargets.plan(engine, scope.subject(), world.getName(),
                world.getWorldConfig().getGameplayConfig(), ProgressionRuntime.objectiveKinds()::isPlaceTargeted,
                PlacementWorlds::wheresOf, Gateways.all(), quest -> ProgressionTexts.titleOrUntitled(quest.id()),
                QuestIndicatorConfig.getInstance().pointerIcon());
        List<WaypointTarget> available;
        if (QuestMarkYield.consumerDraws()) {
            tracked = QuestWaypointTargets.whileConsumerDraws(tracked);
            available = List.of();
        } else {
            available = QuestWaypointTargets.available(QuestIndicators.mapMarks(engine, scope.subject()),
                    quest -> Msg.key(AVAILABLE_TITLE_KEY, ProgressionTexts.titleOrUntitled(quest.id())));
        }
        if (tracked.stream().anyMatch(t -> QuestWaypointTargets.isGatewayKey(t.positionKey()))) {
            Gateways.index(world);
        }
        put(TRACKED, scope.viewerId(), tracked);
        put(AVAILABLE, scope.viewerId(), available);
        WAYPOINTS.refresh(scope.viewerId());
    }

    @Override
    public void forget(@Nonnull UUID viewerId) {
        TRACKED.remove(viewerId);
        AVAILABLE.remove(viewerId);
        WAYPOINTS.clear(viewerId);
    }

    private static void put(@Nonnull ConcurrentHashMap<UUID, List<WaypointTarget>> table, @Nonnull UUID viewerId,
            @Nonnull List<WaypointTarget> targets) {
        if (targets.isEmpty()) {
            table.remove(viewerId);
        } else {
            table.put(viewerId, List.copyOf(targets));
        }
    }
}
