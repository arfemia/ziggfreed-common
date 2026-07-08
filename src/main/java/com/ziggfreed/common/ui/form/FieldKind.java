package com.ziggfreed.common.ui.form;

/**
 * The kind of one {@link SettingsForm} field: which row template it renders as and how its cached
 * raw string value parses into a leaf by {@link SettingsForm#collectLeaves}.
 *
 * <ul>
 *   <li>{@code TEXT} - a free string leaf.
 *   <li>{@code NUMBER} - a non-negative {@code double} leaf.
 *   <li>{@code CHANCE} - a {@code double} leaf clamped to {@code 0..1}.
 *   <li>{@code INT} - an {@code int} leaf of any sign (offsets may be negative).
 *   <li>{@code CSV} - a comma-separated id list, collected as a {@code List<String>} leaf.
 *   <li>{@code TOGGLE} - an instant on/off button; NEVER collected (no leaf).
 *   <li>{@code TRISTATE} - an Inherit/On/Off dropdown collected as {@code Boolean} or {@code null}.
 *   <li>{@code DROPDOWN} - a fixed literal-entry dropdown collected as a {@code String} or {@code null}.
 *   <li>{@code HEADER} - a section heading; label only, never collected.
 *   <li>{@code NOTE} - a wrapping hint line; label only, never collected.
 * </ul>
 */
public enum FieldKind {
    TEXT, NUMBER, CHANCE, INT, CSV, TOGGLE, TRISTATE, DROPDOWN, HEADER, NOTE
}
