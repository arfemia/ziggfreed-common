package com.ziggfreed.common.track.tree;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state {@link TreePage} round-trips on every binding: {@code action} ({@link TreeActions}: close, a tab, a row,
 * or one of the reading page's buttons, which act on whatever row the page shows and so carry no id), the {@code tab}
 * a tab's click names and the {@code row} a row's click names.
 */
public class TreeEventData {

    public String action;
    public String tab;
    public String row;

    public static final BuilderCodec<TreeEventData> CODEC =
            BuilderCodec.builder(TreeEventData.class, TreeEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, value, info) -> data.action = value,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Tab", Codec.STRING),
                            (data, value, info) -> data.tab = value,
                            (data, info) -> data.tab)
                    .add()
                    .append(new KeyedCodec<>("Row", Codec.STRING),
                            (data, value, info) -> data.row = value,
                            (data, info) -> data.row)
                    .add()
                    .build();
}
