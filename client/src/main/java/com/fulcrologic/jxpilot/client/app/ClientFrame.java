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
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.KeyHelp;
import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.ControlSettings;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.game.input.KeyFunctions;
import com.fulcrologic.jxpilot.client.game.input.ClientAction;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.xp.GameConnection;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;

/**
 * One client frame without GL: the whole game-side pipeline {@link GameApp} runs before drawing, in a
 * testable, allocation-free unit.
 * <p>
 * Per {@link #step}: receive ({@link GameConnection#update}), advance the game ({@link ClientGame#frame}),
 * notice a new map, track whether the own ship is in play (shields), send this frame's keys and queued
 * reliable data, follow the own ship with the camera centre, fill the scene and radar
 * ({@link SceneFiller}), and lay out the HUD ({@link Hud}), its directly-painted parts ({@link HudExtras})
 * and the score list panel. Key and character events go through {@link #key}/{@link #character}.
 */
public final class ClientFrame {
    /** Spacing under the radar before the score list (C: buttons + 2; the buttons are not drawn). */
    public static final int SCORE_GAP = 2;

    private final ClientGame game;
    private final GameConnection conn;
    private final InputState input;
    private final TalkLine talk = new TalkLine();
    private final KeyRouter router;
    private final Hud hud = new Hud();
    private final HudFrame viewHud = new HudFrame();
    private final HudFrame panelHud = new HudFrame();
    private final HudFrame helpHud = new HudFrame(4, 4, 320, 160);
    private final KeyHelp help;
    private final String helpHint;
    private final ControlSettings settings;
    private final Msg.Settings settingsMsg = new Msg.Settings();
    private boolean joinedOnce;
    private final SceneData scene;
    private final RadarBlips radar;
    private final SceneFiller filler = new SceneFiller();
    private final HudExtras extras = new HudExtras();
    private final WorldMapState mapState;

    private WorldMap lastMap;
    private int mapVersion;
    private double centreX;
    private double centreY;
    private long lastNow;
    private boolean started;
    private boolean quit;
    private boolean swapScale;
    private boolean toggleSound;
    private int talkWidthFor = -1;

    /** With the default key configuration and the given input (tests). */
    public ClientFrame(ClientGame game, GameConnection conn, InputState input, SceneData scene, RadarBlips radar) {
        this(game, conn, input, KeyConfig.defaults(null), scene, radar);
    }

    /** With a loaded key configuration (bindings, control settings, key help). */
    public ClientFrame(ClientGame game, GameConnection conn, KeyConfig keys, SceneData scene, RadarBlips radar) {
        this(game, conn, new InputState(keys.bindings()), keys, scene, radar);
    }

    private ClientFrame(ClientGame game, GameConnection conn, InputState input, KeyConfig keys, SceneData scene,
                        RadarBlips radar) {
        this.game = game;
        this.conn = conn;
        this.input = input;
        this.scene = scene;
        this.radar = radar;
        this.settings = keys.settings();
        this.router = new KeyRouter(input, talk, settings);
        this.mapState = new WorldMapState(game);
        java.util.List<String> helpKeys = input.bindings().keysyms(KeyFunctions.of(ClientAction.HELP));
        this.helpHint = helpKeys.isEmpty() ? null : helpKeys.get(0) + ": keys";
        this.help = new KeyHelp("Keys (" + keys.source() + ")" + (helpHint != null ? " - " + helpKeys.get(0)
            + " closes" : ""), keys.helpLines());
        conn.sendSettings(settings.copyTo(settingsMsg), 0);
    }

    /**
     * One frame at {@code nowNanos} for a game view of {@code viewW x viewH} logical pixels.
     * <p>
     * BEHAVIOR: elapsed time (0 on the first step) ages messages and HUD timers by {@code dt * gameFps}
     * original frames.
     * BEHAVIOR: order: receive, game frame, map check, shields ({@link InputState#setPlaying}: the newest
     * SELF says the player is in play, C Game_over_action; frames without SELF change nothing), INPUT with the
     * current keys, flush.
     * BEHAVIOR: a new map object bumps {@link #mapVersion()} and centres the view on the first base
     * (the map centre without bases); afterwards the own ship, whenever it exists, is the centre.
     * BEHAVIOR: while not playing the HUD's status line shows the connection's current status; on the first
     * PLAYING frame a message names the key help key ("F1: keys"), unless keyHelp is unbound.
     * BEHAVIOR: lays out the key help overlay ({@link #helpHud()}).
     * BEHAVIOR: fills the scene, radar, view HUD, HUD extras and the score list panel (at
     * {@code (0, scoreTop)}, {@code panelW} wide).
     */
    public void step(long nowNanos, int viewW, int viewH, double zoom, TextMetrics m, int panelW, int scoreTop) {
        double dt = started ? (nowNanos - lastNow) / 1e9 : 0;
        started = true;
        lastNow = nowNanos;
        conn.update(nowNanos);
        game.frame(nowNanos, dt);
        WorldMap map = game.map();
        if (map != lastMap) {
            lastMap = map;
            mapVersion++;
            if (map != null) {
                centreOnStart(map);
            }
        }
        ClientWorld w = game.world();
        boolean inPlay = w.selfPlaying();
        input.setPlaying(inPlay);
        conn.sendInput(input.keys(), 0, nowNanos);
        conn.flush(nowNanos);
        if (w.hasSelf()) {
            centreX = w.selfX();
            centreY = w.selfY();
        }
        filler.fill(scene, radar, game);
        mapState.setLoops(scene.loops);
        if (game.state() != ConnectionState.PLAYING) {
            game.status().clear().append(conn.status());
        } else if (!joinedOnce) {
            joinedOnce = true;
            if (helpHint != null) {
                game.messages().add(Msg.Text.KIND_SERVER, helpHint);
            }
        }
        if (talkWidthFor != viewW) {
            talk.limitWidth(m, Hud.talkWindowWidth(viewW));
            talkWidthFor = viewW;
        }
        hud.zoom = zoom;
        hud.build(viewHud, game, talk, viewW, viewH, m, dt * game.gameFps());
        extras.fill(game, hud, viewW, viewH, zoom, centreX, centreY, scene.loops);
        panelHud.clear();
        hud.scoreboard(panelHud, game.scoreboard(), 0, scoreTop, panelW, m);
        help.layout(helpHud, viewW, viewH, m);
    }

    private void centreOnStart(WorldMap map) {
        if (!map.bases().isEmpty()) {
            Base b = map.bases().get(0);
            centreX = b.x();
            centreY = b.y();
        } else {
            centreX = map.pixelWidth() / 2.0;
            centreY = map.pixelHeight() / 2.0;
        }
    }

    /**
     * A GLFW key event ({@link KeyRouter#key}).
     * <p>
     * BEHAVIOR: a finished chat line is sent with {@link GameConnection#sendChat}; QUIT sets
     * {@link #quitRequested()}; SWAP_SCALE sets {@link #takeSwapScale()}; TOGGLE_SOUND sets
     * {@link #takeToggleSound()}; SETTINGS sends the control settings ({@link GameConnection#sendSettings});
     * SHOW_CONTROLS shows the power and turn speed meters; HELP toggles the key help.
     */
    public void key(int key, int action, int mods, long nowNanos) {
        int ev = router.key(key, action, mods);
        if ((ev & KeyRouter.SETTINGS) != 0) {
            conn.sendSettings(settings.copyTo(settingsMsg), nowNanos);
        }
        if ((ev & KeyRouter.SHOW_CONTROLS) != 0) {
            hud.showControls();
        }
        if ((ev & KeyRouter.HELP) != 0) {
            help.toggle();
        }
        if ((ev & KeyRouter.CHAT) != 0) {
            conn.sendChat(router.chat(), nowNanos);
        }
        if ((ev & KeyRouter.QUIT) != 0) {
            quit = true;
        }
        if ((ev & KeyRouter.SWAP_SCALE) != 0) {
            swapScale = true;
        }
        if ((ev & KeyRouter.TOGGLE_SOUND) != 0) {
            toggleSound = true;
        }
    }

    /** A GLFW character event ({@link KeyRouter#character}). */
    public void character(int codepoint) {
        router.character(codepoint);
    }

    /** Window focus lost: game keys up. */
    public void focusLost() {
        router.focusLost();
    }

    /** BEHAVIOR: leave the server (DISCONNECT) unless the connection is already closed. */
    public void disconnect(long nowNanos) {
        if (conn.state() != ConnectionState.CLOSED) {
            conn.disconnect(nowNanos);
        }
    }

    /** Escape was pressed outside the talk line. */
    public boolean quitRequested() { return quit; }

    /** BEHAVIOR: true once per press of the scale-factor swap key. */
    public boolean takeSwapScale() {
        boolean s = swapScale;
        swapScale = false;
        return s;
    }

    /** BEHAVIOR: true once per press of the sound toggle key. */
    public boolean takeToggleSound() {
        boolean t = toggleSound;
        toggleSound = false;
        return t;
    }

    /** Incremented whenever the game's map object changes (first map, reconnect, map change). */
    public int mapVersion() { return mapVersion; }

    /** World point at the view centre. */
    public double centreX() { return centreX; }

    public double centreY() { return centreY; }

    public ClientGame game() { return game; }

    public GameConnection connection() { return conn; }

    public TalkLine talk() { return talk; }

    public InputState input() { return input; }

    public Hud hud() { return hud; }

    /** The game view's HUD draw list (unscaled window pixels of the view). */
    public HudFrame viewHud() { return viewHud; }

    /** The key help overlay's draw list (empty while hidden; view pixels, unscaled). */
    public HudFrame helpHud() { return helpHud; }

    public KeyHelp keyHelp() { return help; }

    public ControlSettings settings() { return settings; }

    /** The panel's draw list (score list). */
    public HudFrame panelHud() { return panelHud; }

    public HudExtras extras() { return extras; }

    public WorldMapState mapState() { return mapState; }

    public SceneData scene() { return scene; }

    public RadarBlips radar() { return radar; }
}
