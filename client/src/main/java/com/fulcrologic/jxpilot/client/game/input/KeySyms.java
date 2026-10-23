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

import static org.lwjgl.glfw.GLFW.*;

/**
 * X11 keysym names (as used by XPilot's key resources in {@code src/client/default.c}) to GLFW key
 * codes. Only the GLFW constants are used (compile-time ints), so this is pure and needs no native
 * library. Keysyms without a GLFW key ({@code Linefeed}, {@code Select}, {@code KP_Delete},
 * {@code KP_Insert}) map to {@link #NONE}.
 */
public final class KeySyms {
    private KeySyms() {}

    /** No GLFW key. */
    public static final int NONE = -1;

    /**
     * BEHAVIOR: single letters (either case) and digits map to their GLFW keys; F1-F25, KP_0-KP_9 and
     * the named keys below map to their GLFW codes; anything else is {@link #NONE}.
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
        return switch (keysym) {
            case "space" -> GLFW_KEY_SPACE;
            case "Return" -> GLFW_KEY_ENTER;
            case "Tab" -> GLFW_KEY_TAB;
            case "Escape" -> GLFW_KEY_ESCAPE;
            case "BackSpace" -> GLFW_KEY_BACKSPACE;
            case "Delete" -> GLFW_KEY_DELETE;
            case "Caps_Lock" -> GLFW_KEY_CAPS_LOCK;
            case "Shift_L" -> GLFW_KEY_LEFT_SHIFT;
            case "Shift_R" -> GLFW_KEY_RIGHT_SHIFT;
            case "Control_L" -> GLFW_KEY_LEFT_CONTROL;
            case "Control_R" -> GLFW_KEY_RIGHT_CONTROL;
            case "Alt_L", "Meta_L" -> GLFW_KEY_LEFT_ALT;
            case "Alt_R", "Meta_R" -> GLFW_KEY_RIGHT_ALT;
            case "backslash" -> GLFW_KEY_BACKSLASH;
            case "quoteright", "apostrophe" -> GLFW_KEY_APOSTROPHE;
            case "quoteleft", "grave" -> GLFW_KEY_GRAVE_ACCENT;
            case "semicolon" -> GLFW_KEY_SEMICOLON;
            case "bracketleft" -> GLFW_KEY_LEFT_BRACKET;
            case "bracketright" -> GLFW_KEY_RIGHT_BRACKET;
            case "slash" -> GLFW_KEY_SLASH;
            case "comma" -> GLFW_KEY_COMMA;
            case "period" -> GLFW_KEY_PERIOD;
            case "equal" -> GLFW_KEY_EQUAL;
            case "minus" -> GLFW_KEY_MINUS;
            case "Home" -> GLFW_KEY_HOME;
            case "End" -> GLFW_KEY_END;
            case "Prior", "Page_Up" -> GLFW_KEY_PAGE_UP;
            case "Next", "Page_Down" -> GLFW_KEY_PAGE_DOWN;
            case "Up" -> GLFW_KEY_UP;
            case "Down" -> GLFW_KEY_DOWN;
            case "Left" -> GLFW_KEY_LEFT;
            case "Right" -> GLFW_KEY_RIGHT;
            case "Insert" -> GLFW_KEY_INSERT;
            case "Pause" -> GLFW_KEY_PAUSE;
            case "Print" -> GLFW_KEY_PRINT_SCREEN;
            case "KP_Enter" -> GLFW_KEY_KP_ENTER;
            case "KP_Add" -> GLFW_KEY_KP_ADD;
            case "KP_Subtract" -> GLFW_KEY_KP_SUBTRACT;
            case "KP_Multiply" -> GLFW_KEY_KP_MULTIPLY;
            case "KP_Divide" -> GLFW_KEY_KP_DIVIDE;
            case "KP_Decimal" -> GLFW_KEY_KP_DECIMAL;
            default -> NONE;
        };
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
