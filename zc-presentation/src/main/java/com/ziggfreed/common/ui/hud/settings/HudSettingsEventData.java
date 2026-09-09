package com.ziggfreed.common.ui.hud.settings;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * The state the HUD settings page round-trips on every binding, in the one shape every shared
 * settings row speaks: {@code Action} names what happened, {@code Field} which row, and
 * {@code @Value} the row's live value.
 *
 * <p>{@code action} is one of {@code close}, {@code back}, {@code tab} (switch to the tab named in
 * {@code tab}), {@code field} (a dropdown or text field changed), {@code press} (a toggle was
 * clicked) or {@code save} (the Server tab's Save button).
 *
 * <p>{@code value} arrives under the key {@code @Value}. The {@code @} is load-bearing: it is the
 * client's directive to resolve the binding's value as an element path and ship what the control
 * holds. Declared bare, the page would never hear the value at all, and the binding would ship the
 * path string in its place.
 */
public class HudSettingsEventData {

    public String action;
    public String tab;
    public String field;
    public String value;

    public static final BuilderCodec<HudSettingsEventData> CODEC =
            BuilderCodec.builder(HudSettingsEventData.class, HudSettingsEventData::new)
                    .append(new KeyedCodec<>("Action", Codec.STRING),
                            (data, v, info) -> data.action = v,
                            (data, info) -> data.action)
                    .add()
                    .append(new KeyedCodec<>("Tab", Codec.STRING),
                            (data, v, info) -> data.tab = v,
                            (data, info) -> data.tab)
                    .add()
                    .append(new KeyedCodec<>("Field", Codec.STRING),
                            (data, v, info) -> data.field = v,
                            (data, info) -> data.field)
                    .add()
                    .append(new KeyedCodec<>(HudSettingsPage.VALUE_KEY, Codec.STRING),
                            (data, v, info) -> data.value = v,
                            (data, info) -> data.value)
                    .add()
                    .build();
}
