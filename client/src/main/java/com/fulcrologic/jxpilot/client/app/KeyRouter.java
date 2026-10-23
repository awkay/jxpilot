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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.input.ClientAction;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.game.input.KeyBindings;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Routes GLFW key and character events to the game keys ({@link InputState}) or, while the talk line is
 * open, to the {@link TalkLine} editor (C {@code xevent.c} / {@code talk.c}): the talk key opens it,
 * printable characters are typed, Return sends, Escape cancels, Up/Down browse the history, Left/Right
 * move the cursor, Backspace/Delete erase and Ctrl-letters edit. Escape outside the talk line quits.
 * Returns what the app must do as event bits. Pure (only GLFW constants); allocation free except for
 * sending a chat line.
 */
public final class KeyRouter {
    /** Nothing for the app to do. */
    public static final int NONE = 0;
    /** Quit: disconnect and close the window. */
    public static final int QUIT = 1;
    /** A chat line is ready in {@link #chat()}. */
    public static final int CHAT = 2;
    /** Swap scaleFactor and altScaleFactor (C {@code Key_press_swap_scalefactor}). */
    public static final int SWAP_SCALE = 4;

    private final InputState input;
    private final TalkLine talk;
    private final StringBuilder chat = new StringBuilder(TalkLine.MAX_CHARS);
    private boolean swallowChar;

    public KeyRouter(InputState input, TalkLine talk) {
        this.input = input;
        this.talk = talk;
    }

    /**
     * The game's key bindings: XPilot's defaults with Escape taken from C's swap-settings key, since
     * Escape quits here (SPEC "Default controls"), and the scale-factor swap bound to {@code swapKey}
     * (unbound when null, as C's default).
     */
    public static KeyBindings bindings(String swapKey) {
        KeyBindings b = KeyBindings.defaults();
        b.unbind(GLFW_KEY_ESCAPE);
        if (swapKey != null) {
            b.bind(ClientAction.SWAP_SCALEFACTOR, swapKey);
        }
        return b;
    }

    /** The last chat line (valid after a {@link #CHAT} event). */
    public CharSequence chat() {
        return chat;
    }

    /**
     * A GLFW key event.
     * <p>
     * BEHAVIOR (playing): Escape press is {@link #QUIT}; other presses go to {@link InputState#press};
     * a TALK action opens the talk line (game keys released, the key's character is not typed); a
     * SWAP_SCALEFACTOR action is {@link #SWAP_SCALE}; releases go to {@link InputState#release}; repeats
     * are ignored.
     * BEHAVIOR (talking): see {@link #talkKey}; releases are ignored.
     */
    public int key(int key, int action, int mods) {
        if (talk.isOpen()) {
            swallowChar = false;
            return action == GLFW_RELEASE ? NONE : talkKey(key, mods);
        }
        swallowChar = false;
        if (action == GLFW_RELEASE) {
            input.release(key);
            return NONE;
        }
        if (action != GLFW_PRESS) {
            return NONE;
        }
        if (key == GLFW_KEY_ESCAPE) {
            return QUIT;
        }
        long acts = input.press(key);
        int ev = NONE;
        if (KeyBindings.has(acts, ClientAction.TALK)) {
            talk.open();
            input.setTalking(true);
            swallowChar = true;
        }
        if (KeyBindings.has(acts, ClientAction.SWAP_SCALEFACTOR)) {
            ev |= SWAP_SCALE;
        }
        return ev;
    }

    /**
     * A key press or repeat while talking.
     * <p>
     * BEHAVIOR: Return/keypad Enter submits: a non-empty line is copied to {@link #chat()} and is
     * {@link #CHAT}; the line closes and game keys work again either way.
     * BEHAVIOR: Escape cancels (text cleared, line closed). Backspace and Delete erase; Home/End move to
     * the ends; with Control, a letter key is a {@link TalkLine#control} edit.
     * BEHAVIOR: the talk cursor actions (default Left/Right/Up/Down) move the cursor or browse history.
     */
    int talkKey(int key, int mods) {
        switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                boolean sent = talk.submit(chat);
                input.setTalking(false);
                return sent ? CHAT : NONE;
            }
            case GLFW_KEY_ESCAPE -> {
                talk.escape();
                input.setTalking(false);
                return NONE;
            }
            case GLFW_KEY_BACKSPACE -> {
                talk.backspace();
                return NONE;
            }
            case GLFW_KEY_DELETE -> {
                talk.delete();
                return NONE;
            }
            case GLFW_KEY_HOME -> {
                talk.home();
                return NONE;
            }
            case GLFW_KEY_END -> {
                talk.end();
                return NONE;
            }
            default -> { }
        }
        if ((mods & GLFW_MOD_CONTROL) != 0 && key >= GLFW_KEY_A && key <= GLFW_KEY_Z) {
            talk.control((char) ('A' + key - GLFW_KEY_A));
            return NONE;
        }
        long acts = input.press(key);
        if (KeyBindings.has(acts, ClientAction.TALK_CURSOR_LEFT)) {
            talk.left();
        } else if (KeyBindings.has(acts, ClientAction.TALK_CURSOR_RIGHT)) {
            talk.right();
        } else if (KeyBindings.has(acts, ClientAction.TALK_CURSOR_UP)) {
            talk.historyUp();
        } else if (KeyBindings.has(acts, ClientAction.TALK_CURSOR_DOWN)) {
            talk.historyDown();
        }
        return NONE;
    }

    /**
     * A GLFW character event.
     * <p>
     * BEHAVIOR: typed into the open talk line, except the character of the key press that opened it;
     * ignored while not talking.
     */
    public void character(int codepoint) {
        if (swallowChar) {
            swallowChar = false;
            return;
        }
        if (talk.isOpen()) {
            talk.type(codepoint);
        }
    }

    /** BEHAVIOR: window focus lost: every game key is released. */
    public void focusLost() {
        input.releaseAll();
    }
}
