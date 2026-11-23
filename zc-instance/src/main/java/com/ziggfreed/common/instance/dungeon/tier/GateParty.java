package com.ziggfreed.common.instance.dungeon.tier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.AreaMembers;
import com.ziggfreed.common.instance.dungeon.CopyRouter;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.party.Party;
import com.ziggfreed.common.party.ZcParty;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;

/**
 * Where a player's party already is, as their dungeon's gate reads it: for each rung of the ladder, the members who
 * stand in the area the party holds in that dungeon right now ({@link CopyRouter#areaOf}, the area a member who picks
 * that rung is routed into, so "pick it to join them" is exact). A member counts as inside when they have arrived in
 * that very area and not left it ({@link AreaMembers#presentArea}: its copy and its area, never another party's area
 * of the same copy). Reads only: nobody is moved.
 */
final class GateParty {

    private static final String LIST_KEY = "ziggfreedcommon.dungeon.gate.list";

    private GateParty() {
    }

    /** Whether {@code player} is in a party with anyone else in it. */
    static boolean withOthers(@Nonnull UUID player) {
        Party party = ZcParty.service().partyOf(player);
        return party != null && party.size() > 1;
    }

    /**
     * The members of {@code player}'s party (never the player) inside each rung's area, by the rung's id lower-cased,
     * in the ladder's order and the party's order; empty with no party, or nobody inside.
     */
    @Nonnull
    static Map<String, List<UUID>> inside(@Nonnull UUID player, @Nonnull List<TierLadder.Rung> ladder) {
        Party party = ZcParty.service().partyOf(player);
        if (party == null) {
            return Map.of();
        }
        List<UUID> others = new ArrayList<>();
        for (UUID member : party.orderedMembers()) {
            if (!member.equals(player)) {
                others.add(member);
            }
        }
        if (others.isEmpty()) {
            return Map.of();
        }
        String partyKey = CopyRouter.partyKey(party.id(), player);
        Map<String, List<UUID>> out = new LinkedHashMap<>();
        for (TierLadder.Rung rung : ladder) {
            CopyKey area = CopyRouter.shared().areaOf(partyKey, rung.id());
            if (area == null) {
                continue;
            }
            List<UUID> in = new ArrayList<>();
            for (UUID member : others) {
                if (area.equals(AreaMembers.shared().presentArea(member))) {
                    in.add(member);
                }
            }
            if (!in.isEmpty()) {
                out.put(fold(rung.id()), List.copyOf(in));
            }
        }
        return out;
    }

    /** The members of {@code inside} standing in {@code rungId}'s area, or null when none does. */
    @Nullable
    static List<UUID> in(@Nonnull Map<String, List<UUID>> inside, @Nonnull String rungId) {
        return inside.get(fold(rungId));
    }

    /** Players by their names as the page shows them, in order: "Ana, Ben"; null for nobody. */
    @Nullable
    static Message names(@Nonnull List<UUID> players) {
        Message out = null;
        for (UUID player : players) {
            Message name = name(player);
            out = out == null ? name : Msg.key(LIST_KEY, out, name);
        }
        return out;
    }

    /** One player's name as a page shows it, through the one seam every page names a player by. */
    @Nonnull
    static Message name(@Nonnull UUID player) {
        return PlayerDisplayNames.displayName(player, null);
    }

    @Nonnull
    private static String fold(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
