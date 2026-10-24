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

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.game.TalkMacros;
import com.fulcrologic.jxpilot.client.game.input.PlayResources;
import com.fulcrologic.jxpilot.client.game.input.PointerControl;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.xp.GameConnection;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.net.Protocol;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The play keys and pointer of C's client ({@code xevent.c}) that act on the client: talk macros, ID mode,
 * printing the messages, and pointer control with its buttons. {@link ClientFrame} passes {@link KeyRouter} events
 * here; the window passes pointer events. The resources come from the {@link ClientOptions} and follow their changes.
 * Talk macros, printing and an option change allocate; the rest does not.
 */
public final class PlayActions implements TalkMacros.Context {
    private final ClientGame game;
    private final GameConnection conn;
    private final ClientOptions options;
    private final PlayResources res = new PlayResources();
    private int applied;
    private final PrintStream stdout;
    private final PrintStream stderr;
    private final TalkMacros macros = new TalkMacros(this);
    private final PointerControl pointer;
    private final SplittableRandom random;
    private boolean talking;
    private long now;

    /** BEHAVIOR: takes the options at once; pointerControl arms pointer control for the first focus (C). */
    public PlayActions(ClientGame game, GameConnection conn, ClientOptions options, PrintStream stdout,
                       PrintStream stderr, long seed) {
        this.game = game;
        this.conn = conn;
        this.options = options;
        this.stdout = stdout;
        this.stderr = stderr;
        this.random = new SplittableRandom(seed);
        apply();
        this.pointer = new PointerControl(res.pointerControl);
    }

    /** The resources as last taken from the options. */
    public PlayResources resources() { return res; }

    private void apply() {
        applied = options.version();
        res.update(options, stderr::println);
        game.messages().setStdout(res.messagesToStdout, stdout);
    }

    /** BEHAVIOR: takes the options again iff they changed since (the CONFIG panel edits them live). */
    private void refresh() {
        if (options.version() != applied) {
            apply();
        }
    }

    public PointerControl pointer() { return pointer; }

    /**
     * The client side of a key's {@link KeyRouter} events (C {@code Key_press}).
     * <p>
     * BEHAVIOR: ID_MODE toggles the score list's real names; PRINT_MESSAGES prints the messages to stdout;
     * POINTER_CONTROL toggles pointer control; TALK_MACRO sends each pressed macro ({@link TalkMacros#send}) in
     * number order, as the options say now.
     */
    public void handle(int ev, int macroMask, long nowNanos) {
        now = nowNanos;
        refresh();
        if ((ev & KeyRouter.ID_MODE) != 0) {
            game.scoreboard().toggleIdMode();
        }
        if ((ev & KeyRouter.PRINT_MESSAGES) != 0) {
            game.messages().print(stdout, false);
        }
        if ((ev & KeyRouter.POINTER_CONTROL) != 0) {
            pointer.toggle();
        }
        for (int i = 0; i < TalkMacros.COUNT; i++) {
            if ((macroMask & 1 << i) != 0) {
                macros.send(res.talkMacros[i]);
            }
        }
    }

    /**
     * BEHAVIOR: the talk line's state after every key: opening it suspends pointer control, closing it resumes it
     * (C {@code Talk_set_state}).
     */
    public void talk(boolean open) {
        if (open != talking) {
            talking = open;
            if (open) {
                pointer.talkOpened();
            } else {
                pointer.talkClosed();
            }
        }
    }

    /** BEHAVIOR: this frame's pointer movement for {@code GameConnection.sendInput} ({@link PointerControl}). */
    public int takeAim() {
        refresh();
        return pointer.takeMovement(talking);
    }

    /**
     * The X button of a GLFW mouse button: left 1, middle 2, right 3 (X order); others 0 (none of C's five).
     */
    static int xButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW_MOUSE_BUTTON_LEFT -> 1;
            case GLFW_MOUSE_BUTTON_MIDDLE -> 2;
            case GLFW_MOUSE_BUTTON_RIGHT -> 3;
            default -> 0;
        };
    }

    /**
     * C {@code ButtonPress_event} / {@code ButtonRelease_event} under pointer control: X button {@code b}
     * (1..5; 4 and 5 are the wheel) as {@link KeyRouter#pointerButton}.
     * <p>
     * BEHAVIOR: nothing (NONE) unless pointer control is on and not talking, or for a button outside 1..5.
     */
    public int button(KeyRouter router, int b, boolean press) {
        if (!pointer.on() || talking || b < 1 || b > PlayResources.BUTTONS) {
            return KeyRouter.NONE;
        }
        refresh();
        return router.pointerButton(press, res.buttonGame[b - 1], res.buttonActions[b - 1]);
    }

    // ---------------------------------------------------------------- TalkMacros.Context

    @Override
    public Players players() { return game.players(); }

    @Override
    public int selfSlot() {
        Players p = game.players();
        return p.slotOf(p.selfId());
    }

    /**
     * C talkmacros.c {@code #l}: the locked player, or the watched one while snooping.
     * <p>
     * BEHAVIOR: snooping (C {@code snooping = self && eyesId != self->id}): the own player is known and the
     * connection's {@link GameConnection#eyesId} is another player: that player's slot (-1 when unknown). Otherwise
     * the locked player's slot (C {@code lock_id}), -1 without a lock.
     */
    @Override
    public int lockSlot() {
        Players p = game.players();
        int eyes = conn.eyesId();
        if (p.selfId() != Protocol.NO_ID && eyes != Protocol.NO_ID && eyes != p.selfId()) {
            return p.slotOf(eyes);
        }
        int id = game.world().hasSelf() ? game.world().selfLockId() : Protocol.NO_ID;
        return id == Protocol.NO_ID ? -1 : p.slotOf(id);
    }

    @Override
    public boolean teamPlay() { return game.teamPlay(); }

    @Override
    public boolean limitedLives() { return game.options() != null && game.options().getBool(Opt.LIMITED_LIVES); }

    @Override
    public String home() { return System.getenv("HOME"); }

    @Override
    public String readFile(String name) {
        try {
            return Files.readString(Path.of(name), StandardCharsets.ISO_8859_1);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public int random(int n) { return random.nextInt(n); }

    @Override
    public void talk(CharSequence line) { conn.sendChat(line, now); }

    @Override
    public void error(String text) { stderr.println(text); }
}
