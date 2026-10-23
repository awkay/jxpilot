package com.fulcrologic.jxpilot.common.config;

/**
 * Value type of an option, mirroring C {@code enum valType} in {@code defaults.h}.
 * <ul>
 *   <li>{@link #SEC}: value given in seconds; C stores {@code (int)(seconds * FPS)} frames.</li>
 *   <li>{@link #PER_SEC}: value given per second; C stores {@code value / FPS} per frame.</li>
 * </ul>
 */
public enum OptionType {
    VOID, INT, REAL, BOOL, IPOS, STRING, SEC, PER_SEC, LIST
}
