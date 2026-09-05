package com.ziggfreed.common.npc.placement.admin;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state the NPC placement admin page round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code back}, {@code toggle} (flip the placement named
 * in {@code id}), {@code search} (the role filter's Search button), {@code clear} (its Clear
 * button) or {@code place} (stand {@code role} where the player is).
 *
 * <p>{@code search} rides EVERY binding rather than only the Search button's own, because the
 * field's live value is the one piece of page state a click must not discard: pressing Place after
 * typing has to know what was typed, and the page is rebuilt between the two.
 */
public class NpcPlacementAdminEventData {

    public String action;

    /** The placement id a toggle click names. */
    public String id;

    /** The role id a place click names. */
    public String role;

    /**
     * The live contents of the role filter field, arriving under the key {@code @Search}. The
     * {@code @} is load-bearing: it is the client's directive to resolve the binding's value as an
     * element path and ship what the field holds. Declared bare, the page would never hear the
     * text at all, and the binding would ship the path string in its place.
     */
    public String search;

    public static final BuilderCodec<NpcPlacementAdminEventData> CODEC =
            BuilderCodec.builder(NpcPlacementAdminEventData.class, NpcPlacementAdminEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Id", Codec.STRING),
                            (data, value, info) -> data.id = value,
                            (data, info) -> data.id)
                    .add()
                    .append(new KeyedCodec<>("Role", Codec.STRING),
                            (data, value, info) -> data.role = value,
                            (data, info) -> data.role)
                    .add()
                    .append(new KeyedCodec<>(NpcPlacementAdminPage.SEARCH_KEY, Codec.STRING),
                            (data, value, info) -> data.search = value,
                            (data, info) -> data.search)
                    .add()
                    .build();
}
