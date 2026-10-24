/*
 * JXPilot, a Java reimplementation of XPilot, a multiplayer gravity war game.
 * Copyright (C) 2026 Tony Kay and the JXPilot contributors.
 *
 * Derived from XPilot 4.5.5, Copyright (C) 1991-2002 by Bjørn Stabell,
 * Ken Ronny Schouten, Bert Gijsbers and Dick Balaska. JXPilot is an
 * independent derivative port: the original XPilot authors were not involved
 * in it and do not endorse it. Ported to Java and modified starting in 2026;
 * see the git history for the changes.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package com.fulcrologic.jxpilot.client.game.input;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;

/**
 * X11 keysym names (as used by XPilot's key resources in {@code src/client/default.c} and {@code ~/.xpilotrc})
 * to GLFW key codes, and back for display. Only the GLFW constants are used (compile-time ints), so this is
 * pure and needs no native library. Keypad keysyms of the Num Lock off state ({@code KP_Insert},
 * {@code KP_Delete}, {@code KP_Home} ...) map to the same physical keypad keys as their digits (GLFW reports
 * physical keys). Keysyms without a GLFW key ({@code Linefeed}, {@code Select} ...) map to {@link #NONE} but
 * are {@link #known}. The Command/Super
 * keys are {@link #reserved} (macOS keeps them for itself): nothing is ever bound to them.
 */
public final class KeySyms {
    private KeySyms() {}

    /** No GLFW key. */
    public static final int NONE = -1;

    private static final Map<String, Integer> NAMED = new LinkedHashMap<>();
    private static final Set<String> NO_KEY = Set.of("Linefeed", "Select", "Multi_key", "Mode_switch", "Num_Lock",
        "Scroll_Lock", "Break", "Clear", "Help", "Find", "Cancel", "Execute", "Undo", "Redo", "Begin", "KP_Space",
        "KP_Tab", "KP_F1", "KP_F2", "KP_F3", "KP_F4", "KP_Separator");
    private static final Set<String> RESERVED = Set.of("Super_L", "Super_R", "Hyper_L", "Hyper_R", "Command");

    static {
        NAMED.put("space", GLFW_KEY_SPACE);
        NAMED.put("Return", GLFW_KEY_ENTER);
        NAMED.put("Tab", GLFW_KEY_TAB);
        NAMED.put("Escape", GLFW_KEY_ESCAPE);
        NAMED.put("BackSpace", GLFW_KEY_BACKSPACE);
        NAMED.put("Delete", GLFW_KEY_DELETE);
        NAMED.put("Caps_Lock", GLFW_KEY_CAPS_LOCK);
        NAMED.put("Shift_L", GLFW_KEY_LEFT_SHIFT);
        NAMED.put("Shift_R", GLFW_KEY_RIGHT_SHIFT);
        NAMED.put("Control_L", GLFW_KEY_LEFT_CONTROL);
        NAMED.put("Control_R", GLFW_KEY_RIGHT_CONTROL);
        NAMED.put("Alt_L", GLFW_KEY_LEFT_ALT);
        NAMED.put("Meta_L", GLFW_KEY_LEFT_ALT);
        NAMED.put("Alt_R", GLFW_KEY_RIGHT_ALT);
        NAMED.put("Meta_R", GLFW_KEY_RIGHT_ALT);
        NAMED.put("backslash", GLFW_KEY_BACKSLASH);
        NAMED.put("quoteright", GLFW_KEY_APOSTROPHE);
        NAMED.put("apostrophe", GLFW_KEY_APOSTROPHE);
        NAMED.put("quoteleft", GLFW_KEY_GRAVE_ACCENT);
        NAMED.put("grave", GLFW_KEY_GRAVE_ACCENT);
        NAMED.put("semicolon", GLFW_KEY_SEMICOLON);
        NAMED.put("bracketleft", GLFW_KEY_LEFT_BRACKET);
        NAMED.put("bracketright", GLFW_KEY_RIGHT_BRACKET);
        NAMED.put("slash", GLFW_KEY_SLASH);
        NAMED.put("comma", GLFW_KEY_COMMA);
        NAMED.put("period", GLFW_KEY_PERIOD);
        NAMED.put("equal", GLFW_KEY_EQUAL);
        NAMED.put("minus", GLFW_KEY_MINUS);
        NAMED.put("Home", GLFW_KEY_HOME);
        NAMED.put("End", GLFW_KEY_END);
        NAMED.put("Prior", GLFW_KEY_PAGE_UP);
        NAMED.put("Page_Up", GLFW_KEY_PAGE_UP);
        NAMED.put("Next", GLFW_KEY_PAGE_DOWN);
        NAMED.put("Page_Down", GLFW_KEY_PAGE_DOWN);
        NAMED.put("Up", GLFW_KEY_UP);
        NAMED.put("Down", GLFW_KEY_DOWN);
        NAMED.put("Left", GLFW_KEY_LEFT);
        NAMED.put("Right", GLFW_KEY_RIGHT);
        NAMED.put("Insert", GLFW_KEY_INSERT);
        NAMED.put("Pause", GLFW_KEY_PAUSE);
        NAMED.put("Print", GLFW_KEY_PRINT_SCREEN);
        NAMED.put("Menu", GLFW_KEY_MENU);
        NAMED.put("KP_Enter", GLFW_KEY_KP_ENTER);
        NAMED.put("KP_Add", GLFW_KEY_KP_ADD);
        NAMED.put("KP_Subtract", GLFW_KEY_KP_SUBTRACT);
        NAMED.put("KP_Multiply", GLFW_KEY_KP_MULTIPLY);
        NAMED.put("KP_Divide", GLFW_KEY_KP_DIVIDE);
        NAMED.put("KP_Decimal", GLFW_KEY_KP_DECIMAL);
        NAMED.put("KP_Equal", GLFW_KEY_KP_EQUAL);
        // keypad keys with Num Lock off: GLFW reports the physical key whatever the lock state
        NAMED.put("KP_Insert", GLFW_KEY_KP_0);
        NAMED.put("KP_End", GLFW_KEY_KP_1);
        NAMED.put("KP_Down", GLFW_KEY_KP_2);
        NAMED.put("KP_Next", GLFW_KEY_KP_3);
        NAMED.put("KP_Page_Down", GLFW_KEY_KP_3);
        NAMED.put("KP_Left", GLFW_KEY_KP_4);
        NAMED.put("KP_Begin", GLFW_KEY_KP_5);
        NAMED.put("KP_Right", GLFW_KEY_KP_6);
        NAMED.put("KP_Home", GLFW_KEY_KP_7);
        NAMED.put("KP_Up", GLFW_KEY_KP_8);
        NAMED.put("KP_Prior", GLFW_KEY_KP_9);
        NAMED.put("KP_Page_Up", GLFW_KEY_KP_9);
        NAMED.put("KP_Delete", GLFW_KEY_KP_DECIMAL);
    }

    /**
     * BEHAVIOR: single letters (either case) and digits map to their GLFW keys; F1-F25, KP_0-KP_9 and
     * the named keys map to their GLFW codes; anything else is {@link #NONE}.
     */
    public static int glfwKey(String keysym) {
        if (keysym.length() == 1) {
            char c = keysym.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return GLFW_KEY_A + (c - 'a');
            }
            if (c >= 'A' && c <= 'Z') {
                return GLFW_KEY_A + (c - 'A');
            }
            if (c >= '0' && c <= '9') {
                return GLFW_KEY_0 + (c - '0');
            }
            return NONE;
        }
        if (keysym.length() >= 2 && keysym.charAt(0) == 'F' && allDigits(keysym, 1)) {
            int n = Integer.parseInt(keysym.substring(1));
            return n >= 1 && n <= 25 ? GLFW_KEY_F1 + n - 1 : NONE;
        }
        if (keysym.length() == 4 && keysym.startsWith("KP_") && Character.isDigit(keysym.charAt(3))) {
            return GLFW_KEY_KP_0 + (keysym.charAt(3) - '0');
        }
        Integer g = NAMED.get(keysym);
        return g != null ? g : NONE;
    }

    /** BEHAVIOR: true for every keysym {@link #glfwKey} maps, and for the X keysyms that have no GLFW key. */
    public static boolean known(String keysym) {
        return glfwKey(keysym) != NONE || NO_KEY.contains(keysym);
    }

    /** BEHAVIOR: true for the Command/Super/Hyper keysyms, which are never bound. */
    public static boolean reserved(String keysym) {
        return RESERVED.contains(keysym);
    }

    /**
     * A keysym name differing only in case (X keysyms are case-sensitive), for error messages.
     * <p>
     * BEHAVIOR: the named or no-key keysym equal ignoring case, F-keys and keypad digits with the right case;
     * null when none.
     */
    public static String suggest(String keysym) {
        for (String n : NAMED.keySet()) {
            if (n.equalsIgnoreCase(keysym)) {
                return n;
            }
        }
        for (String n : NO_KEY) {
            if (n.equalsIgnoreCase(keysym)) {
                return n;
            }
        }
        String upper = keysym.toUpperCase(Locale.ROOT);
        if (upper.startsWith("KP_") || upper.length() >= 2 && upper.charAt(0) == 'F' && allDigits(upper, 1)) {
            String fixed = upper.startsWith("KP_") ? "KP_" + keysym.substring(3) : upper;
            return glfwKey(fixed) != NONE ? fixed : null;
        }
        return null;
    }

    private static boolean allDigits(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
