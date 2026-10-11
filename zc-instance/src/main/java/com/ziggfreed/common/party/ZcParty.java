package com.ziggfreed.common.party;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.party.page.PartyPageDeps;

/**
 * The library's one default party (R258): a {@link PartyService} every server running this library has, so a
 * feature that groups players (a dungeon's entry, {@code /zigparty}) works on a server running nothing else. A
 * consumer uses it and adds only its own policy (a queue handler on its own page deps, say), never a second
 * service of the same parties (M513).
 *
 * <p>Built on first use, which comes after assets load (a player's command or entry), so its settings read the
 * folded {@code Server/ZiggfreedCommon/Party/ZiggfreedCommon.json}; a settings reload later keeps the service
 * already built, with its parties. Its page deps carry no queue handler, so the party page shows no Queue
 * button and no privacy toggle.
 */
public final class ZcParty {

    /** The game id the service stamps on its parties, and the settings id it reads. */
    public static final String GAME_ID = "ziggfreedcommon";

    /** The settings when no file names {@link #GAME_ID}: five players, invites lasting a minute, anyone invites. */
    private static final PartyConfig FALLBACK = new PartyConfig(5, 60, false);

    @Nullable private static volatile PartyService service;

    @Nullable private static volatile PartyPageDeps pageDeps;

    private ZcParty() {
    }

    /** The default party service, built on the first call. */
    @Nonnull
    public static PartyService service() {
        PartyService built = service;
        if (built == null) {
            synchronized (ZcParty.class) {
                built = service;
                if (built == null) {
                    PartyConfig config = PartySettingsConfig.getInstance().resolveOrDefault(GAME_ID, FALLBACK);
                    built = new PartyService(GAME_ID, config, new ZcPartyMessages());
                    service = built;
                }
            }
        }
        return built;
    }

    /** The deps the library's party page is built from: the default service, its words, no queue handler. */
    @Nonnull
    public static PartyPageDeps pageDeps() {
        PartyPageDeps built = pageDeps;
        if (built == null) {
            synchronized (ZcParty.class) {
                built = pageDeps;
                if (built == null) {
                    built = new PartyPageDeps(service(), new ZcPartyScreenMessages(), null);
                    pageDeps = built;
                }
            }
        }
        return built;
    }

    /** Stop the service's invite timers, if it was ever built; called at the library's stop. */
    public static void shutdown() {
        PartyService built = service;
        if (built != null) {
            built.shutdown();
        }
    }
}
