package com.ziggfreed.common.objectives.runtime;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.ziggfreed.common.factor.ModFactors;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.npc.NpcQuestListHosts;
import com.ziggfreed.common.objectives.book.ObjectiveBookInteractions;
import com.ziggfreed.common.objectives.command.ZigProgressCommand;
import com.ziggfreed.common.objectives.questlist.NpcQuestPages;
import com.ziggfreed.common.objectives.store.ZigProgressComponent;
import com.ziggfreed.common.progress.runtime.ProgressionFactors;
import com.ziggfreed.common.progress.runtime.ProgressionFeedbackHook;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Wires the standalone progression experience this module owns at plugin {@code setup()}: the
 * shared progression runtime and its defaults, the authored feedback moments both engines announce
 * into, and the library's own NPC quest page as the default quest-list host. Three ordered phases,
 * each called once from {@code ZiggfreedCommonPlugin.setup()}, which stays the one authority on
 * call ORDER.
 *
 * <p>This module can host all three because it is the one that sees every end being joined: the
 * engines and their runtime (zc-progression), the persisted per-player store and the book (its
 * own), the feedback engine (zc-presentation), and the quest-list host seam (zc-dialogue).
 */
public final class ProgressionBootstrap {

    private ProgressionBootstrap() {
    }

    /**
     * Wire this library's parts of THE shared progression runtime: the persisted per-player progress
     * component, the Objective Book's interaction Type (which the shipped book item names, so it
     * must be registered before any asset decode), the default registrations plus the player
     * lifecycle and generic producer systems behind {@link ProgressionDefaults#install}, and the
     * {@code /zigprogress} admin family that drives the runtime ({@link ZigProgressCommand}).
     *
     * <p>Every registration is unconditional, and none of them decides anything. There is one
     * runtime per server whoever is on it; a consumer that brings its own store or gates registers
     * them at its own {@code setup()} and outranks the defaults registered here. A component type
     * registered after a world has loaded cannot be read off entities saved carrying it, and an ECS
     * system is a setup-time registration, so neither can wait for that. The five generic producers
     * always run into whichever store is registered, and the component is attached to every player
     * either way, because it also holds what conversations remember. The sixth producer, the
     * instance-round listener, is not an ECS system at all (a round ending is announced about a group
     * of players on the shared bus rather than happening to one entity) and is registered by the same
     * {@code install} beside the five, so one place says what the library produces.
     *
     * <p>The progression READINGS ({@link ProgressionFactors#contribute()}) come next: five factor
     * ids claimed process-wide, so any content anywhere - a storefront, a board, a placement, a
     * conversation, a loot roll - can gate on a finished quest, an earned achievement or the mere
     * presence of a quest another pack ships ({@code quest_known}) with no Java
     * and no dependency on the engine that owns the answer. It is a contribution rather than a
     * registration into one vocabulary, which is why it belongs beside the runtime it reads and not
     * inside any consumer's own setup.
     *
     * <p>{@link ModFactors#contribute()} runs right after it, claiming {@code hytale:mod_installed},
     * the presence reading that lets one authored file be correct both on a server carrying some other
     * mod and on a server without it. It is contributed from here for the same reason: a claim on an
     * id belongs beside the thing that answers it, and the engine plugin table answers for everybody.
     */
    public static void setupProgressionRuntime(@Nonnull PluginBase plugin) {
        try {
            ZigProgressComponent.register(plugin.getEntityStoreRegistry());
            ObjectiveBookInteractions.register(plugin);
            ProgressionDefaults.install(plugin);
            ProgressionFactors.contribute();
            ModFactors.contribute();
            plugin.getCommandRegistry().registerCommand(new ZigProgressCommand());
        } catch (Throwable t) {
            // Naming the kinds because this is the one failure nothing downstream reports: the
            // producers are registered one after another, so a throw part way leaves the rest
            // unregistered and their moments simply stop happening, in silence, for the whole boot.
            SafeLog.warn("[progression] shared runtime wiring failed - some or all of "
                    + ProgressionDefaults.producedKinds()
                    + " will not produce quest or achievement progress this boot", t);
        }
    }

    /**
     * Join the progression engines' lifecycle MOMENTS to the engine that answers them from authored
     * JSON, so a quest completing or an achievement being earned draws its toast, plays its jingle
     * and runs its command with no Java anywhere.
     *
     * <p>The join is made from here for the same reason the dialogue memory store is joined from
     * this module ({@code DialogueBootstrap}): the two ends sit in modules that structurally cannot
     * see each other. The progression module produces a moment and must never learn what a
     * notification is; the presentation module knows what a notification is and must never learn
     * what a quest is. This module sits above both, so both are visible from here.
     *
     * <p>It is a CONTRIBUTION, so a consumer mod that wants to react to the same moments registers
     * its own hook beside this one and both fire. Nothing here can be displaced, and nothing here
     * displaces anybody.
     *
     * <p>Registered through the same LIBRARY-DEFAULT registrar the rest of this library's own
     * registrations use. Rank decides nothing for a contribution, but the rank recorded against an
     * owner NAME is decided by whichever registration under it ran first, and this library's name
     * must always read as a library default whoever got there first.
     *
     * <p>It is registered with its own "do I answer this moment?" question beside its reaction, so a
     * moment nobody authored a file for costs the engine that announced it nothing - which is what
     * lets one be announced on every objective tick.
     */
    public static void registerFeedbackMoments() {
        try {
            ProgressionRuntime.defaults(ProgressionDefaults.OWNER).feedbackHook(
                    ProgressionFeedbackHook.of(FeedbackEngine::fire, FeedbackEngine::answers));
        } catch (Throwable t) {
            SafeLog.warn("[feedback] could not wire the authored feedback moments", t);
        }
    }

    /**
     * The library's own NPC quest page is the DEFAULT target the Quests destination opens - a bare
     * server gets a working quest list from this jar alone. A consumer wanting a different screen
     * registers its own host under its own id; the walk tries hosts in sorted id order.
     */
    public static void registerQuestListHost() {
        try {
            NpcQuestListHosts.register(NpcQuestPages.OWNER, NpcQuestPages.OWNER, NpcQuestPages::open);
        } catch (Throwable t) {
            SafeLog.warn("[questlist] default quest-page host wiring failed", t);
        }
    }
}
