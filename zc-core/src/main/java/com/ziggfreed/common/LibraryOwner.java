package com.ziggfreed.common;

/**
 * The library's own name, as it ATTRIBUTES its registrations in the shared ledgers.
 *
 * <p>{@link #NAME} is an attribution, never an id: every surface that takes it (the dialogue
 * engine's factor slot, a {@code FactorRegistry} claim, the dialogue header vocabulary) stores it
 * only so a refusal log line or an admin diagnostic can say WHICH mod holds a registration. It is
 * never a lookup key, never compared against anything, and never persisted, so a registration made
 * from any module reads back under the same name however the library is assembled.
 *
 * <p>It is deliberately a different word from the no-hyphen {@code "ziggfreedcommon"} the
 * per-domain {@code OWNER} constants carry (e.g. {@code ProgressionDefaults.OWNER},
 * {@code LootFactors.OWNER}, {@code NpcDestinations.OWNER}). Those ARE ids: registry keys, host
 * ids, the {@code mods/ziggfreedcommon/} owner-file directory, the {@code Server/ZiggfreedCommon/}
 * asset namespace - each owned by the module that answers for it, and none of them replaceable by
 * this constant. The two vocabularies never meet in one table; keep it that way by never passing
 * {@link #NAME} where a registry expects a KEY.
 */
public final class LibraryOwner {

    /** Who the library says it is in every shared ledger's attribution slot. */
    public static final String NAME = "ziggfreed-common";

    private LibraryOwner() {
    }
}
