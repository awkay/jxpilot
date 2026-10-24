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

import com.fulcrologic.jxpilot.client.game.hud.LoseItemSelection;
import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.input.ClientAction;
import com.fulcrologic.jxpilot.client.game.input.ControlSettings;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.game.input.KeyBindings;
import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.common.net.ControlKey;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Routes GLFW key and character events to the game keys ({@link InputState}) or, while the talk line is
 * open, to the {@link TalkLine} editor (C {@code xevent.c} / {@code talk.c}): the talk key opens it,
 * printable characters are typed, Return sends, Escape cancels, Up/Down browse the history, Left/Right
 * move the cursor, Backspace/Delete erase and Ctrl-letters edit. Outside the talk line Escape is an ordinary key
 * (C's keySwapSettings by default); keyQuit (unbound by default) and, on macOS, Command-Q quit.
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
    /** Mute or unmute sound ({@link ClientAction#TOGGLE_SOUND}). */
    public static final int TOGGLE_SOUND = 8;
    /** The control settings changed by a power/turn-speed key: send them (C {@code Send_power} ...). */
    public static final int SETTINGS = 16;
    /** The control settings changed (sent or swapped): show the power and turn speed meters (C control_count). */
    public static final int SHOW_CONTROLS = 32;
    /** Toggle the key help overlay (JXPilot keyHelp). */
    public static final int HELP = 64;

    private final InputState input;
    private final TalkLine talk;
    private final ControlSettings settings;
    private final StringBuilder chat = new StringBuilder(TalkLine.MAX_CHARS);
    private final LoseItemSelection loseSelection = new LoseItemSelection();
    private boolean swallowChar;
    /** Command-Q quits (the macOS application quit shortcut). */
    private final boolean commandQuits;

    public KeyRouter(InputState input, TalkLine talk) {
        this(input, talk, new ControlSettings());
    }

    /** @param settings the control settings the power/turn-speed/swap keys change */
    public KeyRouter(InputState input, TalkLine talk, ControlSettings settings) {
        this(input, talk, settings, MAC);
    }

    /** @param commandQuits whether Command(Super)-Q quits (true on macOS) */
    public KeyRouter(InputState input, TalkLine talk, ControlSettings settings, boolean commandQuits) {
        this.input = input;
        this.talk = talk;
        this.settings = settings;
        this.commandQuits = commandQuits;
    }

    /** Running on macOS. */
    static final boolean MAC = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");

    /**
     * The game's default key bindings ({@link KeyConfig#defaults}): XPilot's defaults plus the JXPilot
     * supplement, and the scale-factor swap bound to {@code swapKey} (unbound when null, as C's default).
     */
    public static KeyBindings bindings(String swapKey) {
        return KeyConfig.defaults(swapKey).bindings();
    }

    public ControlSettings settings() {
        return settings;
    }

    public InputState input() {
        return input;
    }

    /** The lose-item outline state the SELECT_ITEM / LOSE_ITEM keys drive (C {@code lose_item_active}). */
    public LoseItemSelection loseSelection() {
        return loseSelection;
    }

    /** The last chat line (valid after a {@link #CHAT} event). */
    public CharSequence chat() {
        return chat;
    }

    /**
     * A GLFW key event.
     * <p>
     * BEHAVIOR (playing): a QUIT action (keyQuit) is {@link #QUIT}, and so is Command(Super)-Q when Command-Q
     * quits (macOS; the key is not sent); presses go to {@link InputState#press} (Escape included: C's
     * keySwapSettings);
     * a TALK action opens the talk line (game keys released, the key's character is not typed); a
     * SWAP_SCALEFACTOR action is {@link #SWAP_SCALE}, a TOGGLE_SOUND action {@link #TOGGLE_SOUND}, a HELP action
     * {@link #HELP}; releases go to {@link InputState#release}; repeats are ignored.
     * BEHAVIOR (playing): increase/decrease power and turn speed change the {@link ControlSettings} as C's
     * key handlers do and are {@link #SETTINGS} | {@link #SHOW_CONTROLS}; swap settings swaps them and is
     * {@link #SHOW_CONTROLS} only (the key goes to the server, which swaps its copy, as in C).
     * BEHAVIOR (playing): each SELECT_ITEM and LOSE_ITEM function bound to a pressed or released key is a
     * {@link LoseItemSelection#press} / {@link LoseItemSelection#release} (C {@code Key_press_select_lose_item},
     * {@code Key_release}); the keys still go to the server.
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
            for (int i = loseKeys(key); i > 0; i--) {
                loseSelection.release();
            }
            return NONE;
        }
        if (action != GLFW_PRESS) {
            return NONE;
        }
        if (commandQuits && key == GLFW_KEY_Q && (mods & GLFW_MOD_SUPER) != 0) {
            return QUIT;
        }
        for (int i = loseKeys(key); i > 0; i--) {
            loseSelection.press();
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
        if (KeyBindings.has(acts, ClientAction.TOGGLE_SOUND)) {
            ev |= TOGGLE_SOUND;
        }
        if (KeyBindings.has(acts, ClientAction.HELP)) {
            ev |= HELP;
        }
        if (KeyBindings.has(acts, ClientAction.QUIT)) {
            ev |= QUIT;
        }
        return ev | controls(input.bindings().gameMask(key));
    }

    /** How many of SELECT_ITEM and LOSE_ITEM the key is bound to (0..2); 0 for out-of-range codes. */
    private int loseKeys(int key) {
        if (key < 0 || key >= KeyBindings.SIZE) {
            return 0;
        }
        long m = input.bindings().gameMask(key);
        return ((m & ControlKey.SELECT_ITEM.mask()) != 0 ? 1 : 0) + ((m & ControlKey.LOSE_ITEM.mask()) != 0 ? 1 : 0);
    }

    private int controls(long m) {
        int ev = NONE;
        if ((m & ControlKey.INCREASE_POWER.mask()) != 0) {
            settings.increasePower();
            ev = SETTINGS | SHOW_CONTROLS;
        }
        if ((m & ControlKey.DECREASE_POWER.mask()) != 0) {
            settings.decreasePower();
            ev = SETTINGS | SHOW_CONTROLS;
        }
        if ((m & ControlKey.INCREASE_TURNSPEED.mask()) != 0) {
            settings.increaseTurnSpeed();
            ev = SETTINGS | SHOW_CONTROLS;
        }
        if ((m & ControlKey.DECREASE_TURNSPEED.mask()) != 0) {
            settings.decreaseTurnSpeed();
            ev = SETTINGS | SHOW_CONTROLS;
        }
        if ((m & ControlKey.SWAP_SETTINGS.mask()) != 0) {
            settings.swap();
            ev |= SHOW_CONTROLS;
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
