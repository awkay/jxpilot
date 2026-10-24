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
import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.KeyHelp;
import com.fulcrologic.jxpilot.client.game.hud.LobbyPanel;
import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.hud.TextSelection;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.ControlSettings;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.game.input.KeyFunctions;
import com.fulcrologic.jxpilot.client.game.input.ClientAction;
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
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
    /** Spacing under the radar before the score list without the button bar (C: buttons + 2). */
    public static final int SCORE_GAP = 2;

    private final ClientGame game;
    private final GameConnection conn;
    private final InputState input;
    private final TalkLine talk = new TalkLine();
    private final KeyRouter router;
    private final Hud hud = new Hud();
    private final HudFrame viewHud = new HudFrame();
    /** The panel: score list, button bar and CONFIG (whose arrows take one rectangle per pixel row). */
    private final HudFrame panelHud = new HudFrame(128, 1536, 320, 256);
    private final HudFrame helpHud = new HudFrame(4, 4, 320, 160);
    private final KeyHelp help;
    /** The map list of a lobby server, shown (instead of the key help) while the connection is in the lobby. */
    private final LobbyPanel lobbyPanel = new LobbyPanel();
    /** {@code --lobby-map}: the map to pick by itself, or null (cleared once tried). */
    private String lobbyPick;
    /** The server's MOTD panel once it arrived (C's MOTD viewer), else null. */
    private KeyHelp motd;
    private final String helpHint;
    private final ControlSettings settings;
    private final Msg.Settings settingsMsg = new Msg.Settings();
    private boolean joinedOnce;
    private final SceneData scene;
    private final RadarBlips radar;
    private final SceneFiller filler = new SceneFiller();
    private final HudExtras extras = new HudExtras();
    private final WorldMapState mapState;
    /** The C client options (client-ui): pushed into the HUD, messages and talk line when they change. */
    private final LiveOptions liveOptions;
    private final PlayActions play;
    // ---- client-ui-look: the look options, live (palette, painter style, extras)
    private final com.fulcrologic.jxpilot.client.render.Palette palette =
        com.fulcrologic.jxpilot.client.render.Palette.xpilotDefault();
    private final com.fulcrologic.jxpilot.client.paint.PaintStyle style =
        new com.fulcrologic.jxpilot.client.paint.PaintStyle();
    private final LookOptions lookOptions;
    private final LookFrame look;
    // ---- end client-ui-look
    /** The button bar, CONFIG panel and information window (client-ui). */
    private final ClientUi ui;
    private String[] modifierBanks;
    /** The MOTD was asked for from the menu: show it when it arrives even without autoServerMotdPopup. */
    private boolean motdAsked;
    /** The resource file "Save Configuration" writes (C {@code Get_xpilotrc_file}). */
    private java.nio.file.Path rcFile = java.nio.file.Path.of(System.getProperty("user.home", "."), ".xpilotrc");

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
    /** {@link #step(long, int, int, double, TextMetrics, int, int)}'s one-metrics adapter, made once per metrics. */
    private TextMetrics singleFor;
    private FontRole.Metrics single;
    /** World view size last given to {@link GameConnection#sendDisplay} (-1: never). */
    private int displayW = -1;
    /** When the server ended the session (refusal, quit packet, timeout), or -1. */
    private long closedAt = -1;
    /** Where C's error texts go (C {@code error()} prints to stderr). */
    private java.io.PrintStream errors = System.err;
    /** How long the window shows the server's last word before the client ends (C exits at once). */
    public static final long CLOSE_LINGER_NANOS = 5_000_000_000L;
    private int sentMaxFps = Integer.MIN_VALUE;
    private int displayH = -1;

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
        this.liveOptions = new LiveOptions(keys.options());
        this.lookOptions = new LookOptions(keys.options(), System.out);
        this.look = new LookFrame(keys.options());
        filler.setStyle(style);
        this.ui = new ClientUi(new UiHost(), keys.options(), settings, keys.maxVolume() >= 0 ? keys.maxVolume()
            : com.fulcrologic.jxpilot.client.sound.SoundPlayer.DEFAULT_MAX_VOLUME);
        this.ui.saveContext("", input.bindings(), keys.modifierBanks());
        java.util.List<String> helpKeys = input.bindings().keysyms(KeyFunctions.of(ClientAction.HELP));
        this.helpHint = helpKeys.isEmpty() ? null : helpKeys.get(0) + ": keys";
        this.help = new KeyHelp("Keys (" + keys.source() + ")" + (helpHint != null ? " - " + helpKeys.get(0)
            + " closes" : ""), keys.helpLines());
        conn.sendSettings(settings.copyTo(settingsMsg), 0);
        conn.setModifierBanks(keys.modifierBanks());
        this.play = new PlayActions(game, conn, keys.options(), System.out, System.err, System.nanoTime());
        this.modifierBanks = keys.modifierBanks();
    }

    /**
     * One frame at {@code nowNanos} for a game view of {@code viewW x viewH} logical pixels.
     * <p>
     * BEHAVIOR: elapsed time (0 on the first step) ages messages and HUD timers by {@code dt * gameFps}
     * original frames.
     * BEHAVIOR: order: receive, game frame, map check, shields ({@link InputState#setPlaying}: the newest
     * SELF says the player is in play, C Game_over_action; frames without SELF change nothing), the view size
     * in world pixels when it changed ({@code GameConnection.sendDisplay}), INPUT with the current keys, flush.
     * BEHAVIOR: a new map object bumps {@link #mapVersion()} and centres the view on the first base
     * (the map centre without bases); afterwards the own ship, whenever it exists, is the centre.
     * BEHAVIOR: while not playing the HUD's status line shows the connection's current status; on the first
     * PLAYING frame a message names the key help key ("F1: keys"), unless keyHelp is unbound.
     * BEHAVIOR: lays out the key help overlay ({@link #helpHud()}), or the server MOTD panel while it is open (it opens
     * once, when the whole MOTD has arrived).
     * BEHAVIOR: fills the scene, radar, view HUD, HUD extras (item timers aged by {@code dt * gameFps} original
     * frames, with the lose-item outline of the
     * {@link KeyRouter#loseSelection}, aged by dt) and the panel under the radar ({@code radarHeight} tall,
     * {@code panelW} wide): the button bar, then the score list window (not while CONFIG covers it), CONFIG and
     * the windows' overlays ({@link ClientUi}).
     * BEHAVIOR: each part uses its role's metrics from {@code fonts}: the HUD as {@link Hud#build}, the talk
     * line's width limit {@link FontRole#TALK} (the line keeps that metrics object, which follows rebakes), the score
     * list {@link FontRole#SCORE_LIST}, the key help {@link FontRole#KEY_LIST}, the MOTD panel {@link FontRole#MOTD},
     * CONFIG's labels and values {@link FontRole#TEXT}, buttons {@link FontRole#BUTTON}.
     */
    public void step(long nowNanos, int viewW, int viewH, double zoom, FontRole.Metrics fonts, int panelW,
                     int radarHeight) {
        double dt = started ? (nowNanos - lastNow) / 1e9 : 0;
        started = true;
        liveOptions.apply(hud, game, extras, talk, filler, input);
        lookOptions.apply(palette, style);
        hud.bitmapMeters = style.texturedObjects;
        lastNow = nowNanos;
        conn.update(nowNanos);
        serverClosed(nowNanos);
        lobbyStep(nowNanos);
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
        connectionOptions(nowNanos);
        sendDisplayIfChanged(viewW, viewH, zoom, nowNanos);
        play.pointer().setWindow(viewW, viewH);
        conn.sendInput(input.takeKeys(), play.takeAim(), nowNanos);
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
            talk.limitWidth(fonts.of(FontRole.TALK), Hud.talkWindowWidth(viewW));
            talkWidthFor = viewW;
        }
        hud.zoom = zoom;
        hud.build(viewHud, game, talk, viewW, viewH, fonts, dt * game.gameFps());
        extras.step(dt * game.gameFps(), game.gameFps());
        extras.fill(game, hud, viewW, viewH, zoom, centreX, centreY, scene.loops);
        extras.loseItem(router.loseSelection(), dt);
        panelHud.clear();
        lastViewW = viewW;
        lastViewH = viewH;
        messageFont = fonts.of(FontRole.MESSAGE);
        talkFont = fonts.of(FontRole.TALK);
        talkHeightFont = fonts.of(FontRole.TEXT);
        TextMetrics textFont = fonts.of(FontRole.TEXT);
        TextMetrics buttonFont = fonts.of(FontRole.BUTTON);
        ui.layout(radarHeight, viewH, viewW, viewH, map != null && map.edgeWrap(), textFont, buttonFont);
        int scoreTop = ui.scoreTop();
        if (!ui.coversScores()) {
            hud.scoreboard(panelHud, game.scoreboard(), 0, scoreTop, panelW,
                style.texturedObjects ? 0 : Math.max(0, viewH - scoreTop), // texturedObjects: bitmaps (GameApp)
                fonts.of(FontRole.SCORE_LIST));
        }
        lookExtras(viewW, fonts.of(FontRole.GAME), fonts.of(FontRole.SCORE_LIST), scoreTop, panelW);
        ui.draw(panelHud, textFont, buttonFont, 0);
        String motdText = game.takeMotd();
        if (motdText != null) {
            motd = motdPanel(motdText);
        }
        if (lobbyActive) {
            lobbyPanel.layout(helpHud, viewW, viewH, fonts.of(FontRole.KEY_LIST));
        } else if (motd != null && motd.visible()) {
            motd.layout(helpHud, viewW, viewH, fonts.of(motd.role()));
        } else {
            help.layout(helpHud, viewW, viewH, fonts.of(help.role()));
        }
    }

    private boolean lobbyActive;

    /**
     * The lobby overlay's per-frame state.
     * <p>
     * BEHAVIOR: while the connection is in the lobby ({@link GameConnection#lobby()}) the panel takes the list,
     * server name and message of the dialogue and is shown; otherwise it is not.
     * BEHAVIOR: with {@link #setLobbyPick} set, the first frame the list is there picks the map of that name (compared
     * without case) once; a name the lobby does not offer is reported to the error stream and the player chooses.
     */
    private void lobbyStep(long nowNanos) {
        com.fulcrologic.jxpilot.client.net.LobbyClient lc = conn.lobby();
        lobbyActive = lc != null;
        if (lc == null) {
            return;
        }
        lobbyPanel.update(lc.serverName(), lc.entries(), lc.message());
        if (lobbyPick != null && lc.greeted() && !lc.entries().isEmpty()) {
            String want = lobbyPick;
            lobbyPick = null;
            com.fulcrologic.jxpilot.common.lobby.LobbyEntry found = null;
            for (com.fulcrologic.jxpilot.common.lobby.LobbyEntry e : lc.entries()) {
                if (e.name().equalsIgnoreCase(want)) {
                    found = e;
                }
            }
            if (found != null) {
                lc.select(found.id(), found.name(), nowNanos);
            } else {
                errors.println("The lobby has no map named '" + want + "'; choose one from the list.");
            }
        }
    }

    /** {@code --lobby-map}: pick this map by name as soon as a lobby server shows its list (null: never). */
    public void setLobbyPick(String mapName) {
        lobbyPick = mapName;
    }

    /** The lobby overlay (its list and cursor). */
    public LobbyPanel lobbyPanel() { return lobbyPanel; }

    /** True while the lobby overlay is up (the connection is in the lobby). */
    public boolean lobbyActive() { return lobbyActive; }

    /**
     * A key while the lobby overlay is up: Up/Down/PageUp/PageDown/Home/End move the cursor (and repeat), Enter
     * chooses the cursor's map, Escape and Command-Q quit; every other key does nothing (the game keys are not
     * live in the lobby).
     */
    private void lobbyKey(int key, int action, int mods, long nowNanos) {
        if (action != org.lwjgl.glfw.GLFW.GLFW_PRESS && action != org.lwjgl.glfw.GLFW.GLFW_REPEAT) {
            return;
        }
        switch (key) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> lobbyPanel.move(-1);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> lobbyPanel.move(1);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> lobbyPanel.page(-1);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> lobbyPanel.page(1);
            case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> lobbyPanel.home();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> lobbyPanel.end();
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER -> {
                if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                    lobbySelect(lobbyPanel.current(), nowNanos);
                }
            }
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE -> quit = true;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_Q -> {
                if ((mods & org.lwjgl.glfw.GLFW.GLFW_MOD_SUPER) != 0 && KeyRouter.MAC) {
                    quit = true;
                }
            }
            default -> { }
        }
    }

    private void lobbySelect(com.fulcrologic.jxpilot.common.lobby.LobbyEntry e, long nowNanos) {
        com.fulcrologic.jxpilot.client.net.LobbyClient lc = conn.lobby();
        if (e != null && lc != null) {
            lc.select(e.id(), e.name(), nowNanos);
        }
    }

    /**
     * client-ui-look: the packet meters in the view HUD (while PLAYING), the clock over the score window (unless a panel covers it), the
     * bell and the title flip of this frame ({@link LookFrame}).
     */
    private void lookExtras(int viewW, TextMetrics gameFont, TextMetrics scoreFont, int scoreTop, int panelW) {
        if (game.state() == ConnectionState.PLAYING) {
            look.packetMeters(viewHud, hud, hud.extent(viewW), conn.packetStats(), gameFont);
        }
        if (!ui.coversScores()) {
            look.clock(panelHud, 0, scoreTop, panelW, scoreFont, scene.loops, scene.fps);
        }
        bell = look.bell(extras.timeLeft);
        titleChange = look.title(scene.loops);
    }

    private boolean bell;
    private int titleChange;

    /** True when this frame rings the bell (last 10 s of game time, {@link LookFrame#bell}). */
    public boolean bell() { return bell; }

    /** This frame's window title change ({@link LookFrame#title}: KEEP, NORMAL or COPYRIGHT). */
    public int titleChange() { return titleChange; }

    /** {@link #step(long, int, int, double, FontRole.Metrics, int, int)} with one metrics for every role (tests). */
    public void step(long nowNanos, int viewW, int viewH, double zoom, TextMetrics m, int panelW, int radarHeight) {
        if (m != singleFor) {
            singleFor = m;
            single = role -> m;
        }
        step(nowNanos, viewW, viewH, zoom, single, panelW, radarHeight);
    }

    /**
     * C's MOTD viewer ("XPilot motd from SERVER"), open until the help key closes it.
     * <p>
     * BEHAVIOR: it opens by itself iff autoServerMotdPopup is on (C default) or the MOTD menu entry asked for it.
     */
    private KeyHelp motdPanel(String text) {
        java.util.List<String[]> lines = new java.util.ArrayList<>();
        for (String line : text.replace("\t", "        ").split("\r?\n", -1)) {
            lines.add(new String[] {"", line});
        }
        KeyHelp k = new KeyHelp("XPilot motd from " + game.serverName() + (helpHint != null
            ? " - " + helpHint.substring(0, helpHint.indexOf(':')) + " closes" : ""), lines, FontRole.MOTD);
        if (motdAsked || liveOptions.options().flag(ClientOption.AUTO_SERVER_MOTD_POPUP)) {
            k.toggle();
        }
        motdAsked = false;
        return k;
    }

    /**
     * M10, C's error presentation: when the connection closes on the server's side (a refusal, a quit packet, a
     * timeout) its status (C's text) is printed to stderr once, as C's {@code error()}; the window keeps showing it
     * for {@link #CLOSE_LINGER_NANOS}, then the client quits (C exits at once).
     */
    private void serverClosed(long nowNanos) {
        if (closedAt < 0 && conn.state() == ConnectionState.CLOSED) {
            closedAt = nowNanos;
            errors.println(conn.status());
        }
        if (closedAt >= 0 && nowNanos - closedAt >= CLOSE_LINGER_NANOS) {
            quit = true;
        }
    }

    /** True once the server ended the session (the launcher then exits with status 1, as C). */
    public boolean closedByServer() { return closedAt >= 0; }

    /** Where C's error texts are printed (tests). */
    public void setErrorStream(java.io.PrintStream err) { errors = err; }

    /**
     * The options the server hears about: a maxFPS change is a frame-rate request (C {@code Config_update_maxFPS}),
     * also passed on the first step. (sparkProb is not sent: JXPilot asks the server for every spark and does C's
     * sparkle itself, so C's {@code Config_update_sparkProb} display resend has nothing to change.)
     */
    private void connectionOptions(long nowNanos) {
        com.fulcrologic.jxpilot.client.game.options.ClientOptions o = liveOptions.options();
        int fps = o.integer(ClientOption.MAX_FPS);
        if (fps != sentMaxFps) {
            sentMaxFps = fps;
            conn.sendFps(fps, nowNanos);
        }
    }

    /**
     * C {@code Send_display}: the world area of the view, {@code (int) (size / zoom + 0.5)} per axis (C: draw size
     * times scaleFactor, rounded), goes to {@link GameConnection#sendDisplay} on the first step and whenever it
     * changes (window resize, scale swap); never again for an unchanged size.
     */
    private void sendDisplayIfChanged(int viewW, int viewH, double zoom, long nowNanos) {
        int w = (int) (viewW / zoom + 0.5);
        int h = (int) (viewH / zoom + 0.5);
        if (w != displayW || h != displayH) {
            displayW = w;
            displayH = h;
            conn.sendDisplay(w, h, nowNanos);
        }
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
     * SHOW_CONTROLS shows the power and turn speed meters; HELP closes an open MOTD panel, else toggles the key help;
     * TOGGLE_ITEMS toggles {@link HudExtras#showItems} (C {@code Key_press_show_items}); FUEL_NOTIFY shows the fuel
     * ({@link Hud#notifyFuel}); TOGGLE_MESSAGES toggles {@link Hud#showMessages} (C {@code Key_press_show_messages}).
     * BEHAVIOR: the play events (ID mode, print, talk macros, pointer control) go to {@link PlayActions#handle};
     * the talk line's state to {@link PlayActions#talk}.
     */
    public void key(int key, int action, int mods, long nowNanos) {
        if (lobbyActive) {
            lobbyKey(key, action, mods, nowNanos);
            return;
        }
        events(router.key(key, action, mods), nowNanos);
    }

    /**
     * A GLFW mouse button under pointer control (C {@code pointerButton1..3}: left, middle, right).
     * <p>
     * BEHAVIOR: as a key bound to the button's functions ({@link PlayActions#button}); nothing without pointer
     * control.
     */
    public void mouseButton(int glfwButton, int action, long nowNanos) {
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS || action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) {
            events(play.button(router, PlayActions.xButton(glfwButton),
                action == org.lwjgl.glfw.GLFW.GLFW_PRESS), nowNanos);
        }
    }

    /** BEHAVIOR: a wheel step is X button 4 (up) or 5 (down), pressed and released (C {@code pointerButton4/5}). */
    public void scroll(double dy, long nowNanos) {
        if (dy == 0) {
            return;
        }
        int b = dy > 0 ? 4 : 5;
        events(play.button(router, b, true), nowNanos);
        events(play.button(router, b, false), nowNanos);
    }

    /** The pointer moved to (x, y) pixels of the game view ({@link com.fulcrologic.jxpilot.client.game.input.PointerControl#moved}). */
    public void cursor(double x, double y) {
        play.pointer().moved(x, y, talk.isOpen());
    }

    /** The window got focus back: a suspended pointer control resumes. */
    public void focusGained() {
        play.pointer().focusGained(talk.isOpen());
    }

    private void events(int ev, long nowNanos) {
        play.handle(ev, router.takeMacros(), nowNanos);
        play.talk(talk.isOpen());
        if ((ev & KeyRouter.SETTINGS) != 0) {
            conn.sendSettings(settings.copyTo(settingsMsg), nowNanos);
        }
        if ((ev & KeyRouter.SHOW_CONTROLS) != 0) {
            hud.showControls();
        }
        if ((ev & KeyRouter.HELP) != 0) {
            if (motd != null && motd.visible()) {
                motd.toggle();
            } else {
                help.toggle();
            }
        }
        if ((ev & KeyRouter.PASTE) != 0) {
            paste();
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
        if ((ev & KeyRouter.TOGGLE_ITEMS) != 0) {
            // C Key_press_toggle_owned_items flips the showItems instrument itself (CONFIG and Save see it)
            liveOptions.options().setFlag(ClientOption.SHOW_ITEMS, !liveOptions.options().flag(ClientOption.SHOW_ITEMS));
            liveOptions.apply(hud, game, extras, talk, filler, input);
        }
        if ((ev & KeyRouter.FUEL_NOTIFY) != 0) {
            hud.notifyFuel(game.gameFps());
        }
        if ((ev & KeyRouter.TOGGLE_MESSAGES) != 0) {
            // C Key_press_toggle_messages flips the showMessages instrument
            liveOptions.options().setFlag(ClientOption.SHOW_MESSAGES,
                !liveOptions.options().flag(ClientOption.SHOW_MESSAGES));
            liveOptions.apply(hud, game, extras, talk, filler, input);
        }
    }

    /**
     * A GLFW mouse button event at window position (x, y) (logical pixels, origin top-left).
     * <p>
     * BEHAVIOR: under pointer control the button is a pointer button ({@link #mouseButton(int, int, long)}, C's
     * grab); otherwise the C windows ({@link ClientUi#mouseButton}) get it; returns whether it was used.
     *
     * @param viewX where the game view starts (the panel's width plus the gap)
     */
    public boolean mouseButton(int button, int action, double x, double y, int viewX) {
        if (play.pointer().grabbed()) {
            mouseButton(button, action, System.nanoTime()); // C: the grabbed pointer belongs to the game view
            return true;
        }
        return ui.mouseButton(button, action, x, y, viewX);
    }

    /**
     * BEHAVIOR: pointer motion: under pointer control it steers ({@link #cursor}, view coordinates); otherwise the
     * C windows first ({@link ClientUi#mouseMove}); returns whether it was used.
     */
    public boolean mouseMove(double x, double y, int viewX) {
        if (play.pointer().grabbed()) {
            cursor(x - viewX, y);
            return true;
        }
        return ui.mouseMove(x, y, viewX);
    }

    /** The system clipboard (C's X selection): GLFW's in the game, a fake in tests. */
    public interface Clipboard {
        /** The clipboard text, or null. */
        String get();

        void set(String text);
    }

    private Clipboard clipboard = new Clipboard() {
        private String text;

        @Override
        public String get() { return text; }

        @Override
        public void set(String t) { text = t; }
    };
    private final int[] hit = new int[3];
    private int lastViewW;
    private int lastViewH;
    private TextMetrics messageFont;
    private TextMetrics talkFont;
    private TextMetrics talkHeightFont;

    /** BEHAVIOR: the clipboard cut and paste use from now on. */
    public void setClipboard(Clipboard c) { clipboard = c; }

    /**
     * C's cut and paste with the mouse in the game view ({@code selectionAndHistory}; {@code xeventhandlers.c}
     * {@code ButtonPress_event}/{@code ButtonRelease_event}), for events the windows did not take; (x, y) in view
     * coordinates.
     * <p>
     * BEHAVIOR: nothing (false) unless selectionAndHistory is on. Left press: in the open talk window starts a
     * talk-line selection, on a message a message selection, elsewhere clears the selection; motion with the button
     * down extends it; release ends it and puts its text on the clipboard when not empty. Middle press while the
     * talk line is open: the cursor moves to the char under the pointer when inside the talk window, then the
     * clipboard is pasted. Returns whether the event was used.
     */
    public boolean viewMouse(int button, int action, int x, int y) {
        if (!liveOptions.options().flag(ClientOption.SELECTION_AND_HISTORY) || messageFont == null) {
            return false;
        }
        TextSelection sel = hud.selection;
        if (button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) {
                int c = talk.isOpen() ? hud.talkCharAt(x, y, talk, lastViewW, lastViewH, talkFont, talkHeightFont) : -1;
                if (c >= 0) {
                    sel.start(TextSelection.TALK_LINE, 0, c);
                } else if (hud.messageAt(x, y, game.messages(), lastViewH, messageFont, hit)) {
                    sel.start(hit[0], hit[1], hit[2]);
                } else {
                    sel.clear();
                    return false;
                }
                return true;
            }
            if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE && sel.dragging()) {
                sel.finish();
                String text = sel.text(talk, game.messages());
                if (!text.isEmpty()) {
                    clipboard.set(text);
                }
                return true;
            }
            return false;
        }
        if (button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE && action == org.lwjgl.glfw.GLFW.GLFW_PRESS
            && talk.isOpen()) {
            int c = hud.talkCharAt(x, y, talk, lastViewW, lastViewH, talkFont, talkHeightFont);
            if (c >= 0) {
                talk.setCursor(c);
            }
            paste();
            return true;
        }
        return false;
    }

    /** BEHAVIOR: pointer motion in the view extends a selection being dragged (view coordinates). */
    public boolean viewMouseMove(int x, int y) {
        TextSelection sel = hud.selection;
        if (!sel.dragging() || messageFont == null) {
            return false;
        }
        if (sel.area() == TextSelection.TALK_LINE) {
            sel.drag(TextSelection.TALK_LINE, 0, Hud.charAt(talk.text(), x - (Hud.TALK_WINDOW_X
                + Hud.TALK_OUTSIDE_BORDER + Hud.TALK_INSIDE_BORDER), talkFont));
        } else if (hud.messageAt(x, y, game.messages(), lastViewH, messageFont, hit)) {
            sel.drag(hit[0], hit[1], hit[2]);
        }
        return true;
    }

    private void paste() {
        String text = clipboard.get();
        if (text != null && talk.isOpen()) {
            talk.paste(text);
        }
    }

    /** The client frame as the C windows' {@link ClientUi.Host}. */
    private final class UiHost implements ClientUi.Host {
        @Override
        public void quit() {
            quit = true;
        }

        @Override
        public void toggleKeys() {
            help.toggle();
        }

        @Override
        public void showMotd(boolean refresh) {
            if (refresh || motd == null) {
                motdAsked = conn.requestMotd(lastNow) || motd == null;
            }
            if (motd != null && !motd.visible()) {
                motd.toggle();
            }
        }

        @Override
        public void showRealName(boolean on) {
            game.scoreboard().setIdMode(on);
        }

        @Override
        public void settingsChanged(boolean meters) {
            conn.sendSettings(settings.copyTo(settingsMsg), lastNow);
            if (meters) {
                hud.showControls();
            }
        }

        @Override
        public int serverFps() {
            return (int) Math.round(game.gameFps());
        }

        @Override
        public boolean save(java.util.List<com.fulcrologic.jxpilot.client.game.options.RcWriter.Resource> resources) {
            try {
                com.fulcrologic.jxpilot.client.game.options.RcWriter.save(rcFile, resources);
                game.messages().add(Msg.Text.KIND_SERVER, "Configuration saved to " + rcFile + ".");
                return true;
            } catch (java.io.IOException | RuntimeException e) {
                game.messages().add(Msg.Text.KIND_SERVER, "Saving failed: " + e.getMessage());
                return false;
            }
        }
    }

    /** The button bar, CONFIG panel and information window. */
    public ClientUi ui() { return ui; }

    /** C {@code showRealName}: the score list shows NICK=USER@HOST (MENU / PLAYER) instead of nicks (MENU / SCORE). */
    public boolean showRealName() { return game.scoreboard().idMode(); }

    /** BEHAVIOR: sets C {@code showRealName} (keyIdMode toggles it). */
    public void setShowRealName(boolean on) { game.scoreboard().setIdMode(on); }

    /** The file "Save Configuration" writes, and the player name it saves. */
    public void setSaveTarget(java.nio.file.Path file, String playerName) {
        rcFile = file;
        ui.saveContext(playerName, input.bindings(), modifierBanks);
    }

    /** A GLFW character event ({@link KeyRouter#character}). */
    public void character(int codepoint) {
        if (lobbyActive) {
            lobbySelect(lobbyPanel.choose(codepoint), lastNow);
            return;
        }
        router.character(codepoint);
    }

    /** Window focus lost: game keys up, pointer control suspended. */
    public void focusLost() {
        router.focusLost();
        play.pointer().focusLost();
    }

    /** The play keys and pointer control. */
    public PlayActions play() { return play; }

    /** BEHAVIOR: leave the server (DISCONNECT) unless the connection is already closed. */
    public void disconnect(long nowNanos) {
        if (conn.state() != ConnectionState.CLOSED) {
            conn.disconnect(nowNanos);
        }
    }

    /** A quit was asked for: keyQuit, Command-Q on macOS, or the QUIT button. */
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

    /** The C client options in use (the key configuration's, changed live by the CONFIG panel). */
    public com.fulcrologic.jxpilot.client.game.options.ClientOptions options() { return liveOptions.options(); }

    public SceneData scene() { return scene; }

    /** The live palette ({@code color0..15}, {@code maxColors}). */
    public com.fulcrologic.jxpilot.client.render.Palette palette() { return palette; }

    /** The live look options the painters read. */
    public com.fulcrologic.jxpilot.client.paint.PaintStyle style() { return style; }

    /** The look extras of this frame: packet meters, clock, bell, title flip ({@link LookFrame}). */
    public LookFrame look() { return look; }

    public RadarBlips radar() { return radar; }
}
