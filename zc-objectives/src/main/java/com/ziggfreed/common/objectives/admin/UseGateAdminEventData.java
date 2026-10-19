package com.ziggfreed.common.objectives.admin;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state the Use Gates admin page round-trips on every binding.
 *
 * <p>{@code action} is one of {@code close}, {@code back}, {@code page} (show the page {@code page} names),
 * {@code gate} (switch the gate {@code id} names) or {@code switch} (switch the feature {@code id} names, as
 * {@code <factor>|<feature>}). {@code page} rides every binding, so a switch keeps the admin on the page they
 * were reading.
 */
public class UseGateAdminEventData {

    public String action;

    /** The gate id, or the feature key, a switch names. */
    public String id;

    /** The page of the list the binding was painted on, zero-based, as text. */
    public String page;

    public static final BuilderCodec<UseGateAdminEventData> CODEC =
            BuilderCodec.builder(UseGateAdminEventData.class, UseGateAdminEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Id", Codec.STRING),
                            (data, value, info) -> data.id = value,
                            (data, info) -> data.id)
                    .add()
                    .append(new KeyedCodec<>("Page", Codec.STRING),
                            (data, value, info) -> data.page = value,
                            (data, info) -> data.page)
                    .add()
                    .build();
}
