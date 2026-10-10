package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.NativeReader;

/**
 * A player's standing with each reputation, read for whoever ranks it (the player stat store's
 * {@value #STAT_ID} stat) through the service, so the engine is asked through this module's one door
 * ({@link ReputationNative}) and never read twice. A line per reputation the player has met, filed under the
 * engine's own spelling of its id and holding the EARNED standing, the engine's own record (gear's share
 * comes and goes with what is worn); no total, since standings with unrelated reputations do not add up.
 * Every switched-on reputation is a thing it names, by Order then id, each by its own name, so a section can
 * give each reputation its own column. Off (the switch, or the engine's plugin), it reads nothing.
 */
public final class ReputationStandingReader implements NativeReader {

    /** The stat this reads, shipped as {@code Server/ZiggfreedCommon/Stats/Reputation.json}. */
    public static final String STAT_ID = "Reputation";

    private static final Comparator<ReputationDef> SHOWN_ORDER = Comparator.comparingInt(ReputationDef::order)
            .thenComparing(def -> def.id().toLowerCase(Locale.ROOT));

    private final ReputationService service;

    public ReputationStandingReader(@Nonnull ReputationService service) {
        this.service = service;
    }

    @Override
    @Nullable
    public Reading read(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        if (!service.isOn()) {
            return null;
        }
        Map<String, Long> lines = new LinkedHashMap<>();
        for (ReputationService.Standing standing : service.met(store, ref)) {
            lines.put(standing.reputation().id(), (long) standing.earned());
        }
        return new Reading(null, lines);
    }

    @Override
    @Nonnull
    public List<String> things() {
        List<ReputationDef> all = new ArrayList<>(service.all());
        all.sort(SHOWN_ORDER);
        List<String> out = new ArrayList<>(all.size());
        for (ReputationDef def : all) {
            out.add(def.id());
        }
        return out;
    }

    @Override
    @Nullable
    public String thingNameKey(@Nonnull String thing) {
        ReputationDef def = service.known(thing);
        return def == null ? null : def.titleKey();
    }
}
