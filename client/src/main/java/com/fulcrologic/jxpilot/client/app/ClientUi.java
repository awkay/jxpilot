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

import com.fulcrologic.jxpilot.client.game.MessageLog;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.ControlSettings;
import com.fulcrologic.jxpilot.client.game.input.KeyBindings;
import com.fulcrologic.jxpilot.client.game.input.KeyFunctions;
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.game.options.RcWriter;
import com.fulcrologic.jxpilot.client.game.ui.AboutWindow;
import com.fulcrologic.jxpilot.client.game.ui.ButtonBar;
import com.fulcrologic.jxpilot.client.game.ui.ConfigEntry;
import com.fulcrologic.jxpilot.client.game.ui.ConfigPanel;
import com.fulcrologic.jxpilot.client.game.ui.UiAction;
import com.fulcrologic.jxpilot.common.Const;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.options.ClientOption.*;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * The C client's windows besides the game view and radar: the button bar (QUIT, ABOUT, MENU), the CONFIG panel
 * over the score list, and the information window ({@link AboutWindow}). Routes the mouse to them (C delivers
 * pointer events to the widget windows; a pressed widget keeps the pointer until the release) and turns their
 * actions into calls on a {@link Host}. The CONFIG entries are C's {@code config_creator} list, bound to the
 * {@link ClientOptions}, the {@link ControlSettings} and the host. Allocation free per frame.
 */
public final class ClientUi {
    /** What the windows act on (the client frame). */
    public interface Host {
        /** QUIT. */
        void quit();

        /** MENU / KEYS. */
        void toggleKeys();

        /** MENU / MOTD: show the server MOTD; {@code refresh}: ask the server for a fresh copy (C refreshMotd). */
        void showMotd(boolean refresh);

        /** MENU / SCORE (false) or PLAYER (true): C {@code showRealName}. */
        void showRealName(boolean on);

        /** A control setting changed in CONFIG: send them; {@code meters}: power or turn speed (C control_count). */
        void settingsChanged(boolean meters);

        /** The server's frame rate (C {@code FPS}). */
        int serverFps();

        /** CONFIG "Save Configuration": write the resources; false on failure. */
        boolean save(List<RcWriter.Resource> resources);
    }

    private final Host host;
    private final ClientOptions options;
    private final ControlSettings settings;
    private final ButtonBar bar = new ButtonBar();
    private final AboutWindow about = new AboutWindow();
    private ConfigPanel config;
    private boolean wrapMap;
    private int maxVolume;
    private final HudFrame panelOverlay = new HudFrame(8, 16, 16, 16);
    private final HudFrame aboutHud = new HudFrame(8, 64, 128, 160);
    private int laidFormY = -1;
    private int laidFormH = -1;
    private TextMetrics laidText;
    private TextMetrics laidButton;
    /** The widget that took the last press: 0 none, 1 about, 2 bar, 3 config. */
    private int holder;

    /** @param maxVolume the starting C {@code maxVolume} (0..255) */
    public ClientUi(Host host, ClientOptions options, ControlSettings settings, int maxVolume) {
        this.host = host;
        this.options = options;
        this.settings = settings;
        this.maxVolume = maxVolume;
        this.config = new ConfigPanel(entries(false));
    }

    public ButtonBar bar() { return bar; }

    public ConfigPanel config() { return config; }

    public AboutWindow about() { return about; }

    /** The pulldown menu's draw list (drawn over the panel). */
    public HudFrame panelOverlay() { return panelOverlay; }

    /** The information window's draw list (drawn over the game view). */
    public HudFrame aboutHud() { return aboutHud; }

    /** C {@code maxVolume} as CONFIG last set it. */
    public int maxVolume() { return maxVolume; }

    /**
     * The CONFIG entries in C's order ({@code configure.c} {@code config_creator}, with SOUND and WINDOWSCALING
     * built in, threadedDraw left out as Windows-only); slidingRadar only on edge-wrap maps (C
     * {@code Config_create_slidingRadar}).
     */
    List<ConfigEntry> entries(boolean wrap) {
        List<ConfigEntry> e = new ArrayList<>();
        e.add(control("power", Const.MIN_PLAYER_POWER, Const.MAX_PLAYER_POWER, 0, true));
        e.add(control("turnSpeed", Const.MIN_PLAYER_TURNSPEED, Const.MAX_PLAYER_TURNSPEED, 1, true));
        e.add(control("turnResistance", Const.MIN_PLAYER_TURNRESISTANCE, Const.MAX_PLAYER_TURNRESISTANCE, 2, false));
        e.add(control("altPower", Const.MIN_PLAYER_POWER, Const.MAX_PLAYER_POWER, 3, false));
        e.add(control("altTurnSpeed", Const.MIN_PLAYER_TURNSPEED, Const.MAX_PLAYER_TURNSPEED, 4, false));
        e.add(control("altTurnResistance", Const.MIN_PLAYER_TURNRESISTANCE, Const.MAX_PLAYER_TURNRESISTANCE, 5, false));
        e.add(flag("showMessages", SHOW_MESSAGES));
        e.add(number("maxMessages", ConfigEntry.Kind.INT, MAX_MESSAGES, 1, MessageLog.MAX_MSGS));
        e.add(number("messagesToStdout", ConfigEntry.Kind.INT, MESSAGES_TO_STDOUT, 0, 2));
        e.add(flag("reverseScroll", REVERSE_SCROLL));
        e.add(color("oldMessagesColor", OLD_MESSAGES_COLOR, 0));
        e.add(flag("showHUD", SHOW_HUD));
        e.add(flag("showHUDRadar", SHOW_HUD_RADAR));
        e.add(flag("horizontalHUDLine", HORIZONTAL_HUD_LINE));
        e.add(flag("verticalHUDLine", VERTICAL_HUD_LINE));
        e.add(number("speedFactHUD", ConfigEntry.Kind.FLOAT, SPEED_FACT_HUD, -10, 10));
        e.add(number("speedFactPTR", ConfigEntry.Kind.FLOAT, SPEED_FACT_PTR, -10, 10));
        e.add(number("fuelNotify", ConfigEntry.Kind.INT, FUEL_NOTIFY, 0, 1000));
        e.add(number("fuelWarning", ConfigEntry.Kind.INT, FUEL_WARNING, 0, 1000));
        e.add(number("fuelCritical", ConfigEntry.Kind.INT, FUEL_CRITICAL, 0, 1000));
        e.add(flag("fuelGauge", FUEL_GAUGE));
        e.add(flag("outlineWorld", OUTLINE_WORLD));
        e.add(flag("filledWorld", FILLED_WORLD));
        e.add(flag("texturedWalls", TEXTURED_WALLS));
        e.add(flag("texturedObjects", TEXTURED_OBJECTS));
        if (wrap) {
            e.add(flag("slidingRadar", SLIDING_RADAR));
        }
        e.add(flag("showItems", SHOW_ITEMS));
        e.add(number("showItemsTime", ConfigEntry.Kind.FLOAT, SHOW_ITEMS_TIME, 0, 10));
        e.add(number("showScoreDecimals", ConfigEntry.Kind.INT, SHOW_SCORE_DECIMALS, 0, 2));
        e.add(number("backgroundPointDist", ConfigEntry.Kind.INT, BACKGROUND_POINT_DIST, 0, 10));
        e.add(number("backgroundPointSize", ConfigEntry.Kind.INT, BACKGROUND_POINT_SIZE, 0, 8));
        e.add(number("sparkSize", ConfigEntry.Kind.INT, SPARK_SIZE, 1, 8));
        e.add(number("sparkProb", ConfigEntry.Kind.FLOAT, SPARK_PROB, 0, 1));
        e.add(number("charsPerSecond", ConfigEntry.Kind.INT, CHARS_PER_SECOND, 10, 255));
        e.add(flag("markingLights", MARKING_LIGHTS));
        e.add(flag("toggleShield", TOGGLE_SHIELD));
        e.add(flag("autoShield", AUTO_SHIELD));
        e.add(flag("nastyShots", SHOW_NASTY_SHOTS));
        e.add(number("shotSize", ConfigEntry.Kind.INT, SHOT_SIZE, 1, 8));
        e.add(number("teamShotSize", ConfigEntry.Kind.INT, TEAM_SHOT_SIZE, 1, 8));
        e.add(color("hudColor", HUD_COLOR, 1));
        e.add(color("hudLockColor", HUD_LOCK_COLOR, 1));
        e.add(color("wallColor", WALL_COLOR, 1));
        e.add(color("decorColor", DECOR_COLOR, 1));
        e.add(flag("showDecor", SHOW_DECOR));
        e.add(flag("outlineDecor", OUTLINE_DECOR));
        e.add(flag("filledDecor", FILLED_DECOR));
        e.add(flag("texturedDecor", TEXTURED_DECOR));
        e.add(flag("texturedBalls", TEXTURED_BALLS));
        e.add(new ConfigEntry("maxFPS", ConfigEntry.Kind.INT, 1, 255, new ConfigEntry.Value() {
            @Override
            public double get() {
                return maxFps();
            }

            @Override
            public void set(double v) {
                int fps = Math.max(1, host.serverFps());
                options.setInteger(MAX_FPS, Math.max(fps / 2, Math.min(fps, (int) v)));
            }
        }));
        e.add(new ConfigEntry("maxVolume", ConfigEntry.Kind.INT, 0, 255, new ConfigEntry.Value() {
            @Override
            public double get() {
                return maxVolume;
            }

            @Override
            public void set(double v) {
                maxVolume = (int) v;
            }
        }));
        e.add(flag("showShipName", SHOW_SHIP_NAME));
        e.add(flag("showMineName", SHOW_MINE_NAME));
        e.add(flag("fuelMeter", FUEL_METER));
        e.add(flag("powerMeter", POWER_METER));
        e.add(flag("turnSpeedMeter", TURN_SPEED_METER));
        e.add(flag("packetSizeMeter", PACKET_SIZE_METER));
        e.add(flag("packetLossMeter", PACKET_LOSS_METER));
        e.add(flag("packetDropMeter", PACKET_DROP_METER));
        e.add(flag("packetLagMeter", PACKET_LAG_METER));
        e.add(flag("clock", CLOCK));
        e.add(flag("clockAMPM", CLOCK_AMPM));
        e.add(number("scaleFactor", ConfigEntry.Kind.FLOAT, SCALE_FACTOR, 0.2, 8));
        e.add(number("altScaleFactor", ConfigEntry.Kind.FLOAT, ALT_SCALE_FACTOR, 0.2, 8));
        e.add(flag("shotTracers", SHOW_SHOT_TRACERS)); // JXPilot only, after C's
        return e;
    }

    /**
     * C's maxFPS as CONFIG shows it.
     * <p>
     * BEHAVIOR: the maxFPS option limited to FPS/2..FPS (C {@code Check_client_fps}).
     */
    int maxFps() {
        int fps = Math.max(1, host.serverFps());
        int v = options.integer(MAX_FPS);
        return Math.max(fps / 2, Math.min(fps, v));
    }

    private ConfigEntry flag(String label, ClientOption o) {
        return ConfigEntry.bool(label, new ConfigEntry.Value() {
            @Override
            public double get() {
                return options.flag(o) ? 1 : 0;
            }

            @Override
            public void set(double v) {
                options.setFlag(o, v != 0);
            }
        });
    }

    private ConfigEntry number(String label, ConfigEntry.Kind kind, ClientOption o, double min, double max) {
        return new ConfigEntry(label, kind, min, max, new ConfigEntry.Value() {
            @Override
            public double get() {
                return options.real(o);
            }

            @Override
            public void set(double v) {
                options.setReal(o, v);
            }
        });
    }

    /** C's colour entries: min..maxColors - 1. */
    private ConfigEntry color(String label, ClientOption o, int min) {
        return new ConfigEntry(label, ConfigEntry.Kind.INT, min, 15, new ConfigEntry.Value() {
            @Override
            public double get() {
                return options.integer(o);
            }

            @Override
            public void set(double v) {
                options.setInteger(o, Math.min((int) v, options.integer(MAX_COLORS) - 1));
            }
        });
    }

    /** A control setting (index 0..5: power, turnSpeed, turnResistance and the alternates). */
    private ConfigEntry control(String label, double min, double max, int which, boolean meters) {
        return new ConfigEntry(label, ConfigEntry.Kind.FLOAT, min, max, new ConfigEntry.Value() {
            @Override
            public double get() {
                return switch (which) {
                    case 0 -> settings.power;
                    case 1 -> settings.turnSpeed;
                    case 2 -> settings.turnResistance;
                    case 3 -> settings.altPower;
                    case 4 -> settings.altTurnSpeed;
                    default -> settings.altTurnResistance;
                };
            }

            @Override
            public void set(double v) {
                switch (which) {
                    case 0 -> settings.power = v;
                    case 1 -> settings.turnSpeed = v;
                    case 2 -> settings.turnResistance = v;
                    case 3 -> settings.altPower = v;
                    case 4 -> settings.altTurnSpeed = v;
                    default -> settings.altTurnResistance = v;
                }
                host.settingsChanged(meters);
            }
        });
    }

    /**
     * Lay the windows out for this frame.
     * <p>
     * BEHAVIOR: the bar under the radar; the CONFIG pages for the form below the bar down to the window bottom,
     * re-laid only when the form, the fonts or the map's edge wrap changed (the entries are rebuilt when the wrap
     * changed, keeping the panel open); the information window centred in the view.
     *
     * @param panelH the window height (C {@code top_height})
     */
    public void layout(int radarHeight, int panelH, int viewW, int viewH, boolean wrap, TextMetrics text,
                       TextMetrics button) {
        bar.layout(radarHeight, button);
        int formY = radarHeight + bar.height();
        int formH = Math.max(0, panelH - formY);
        if (wrap != wrapMap) {
            wrapMap = wrap;
            boolean wasOpen = config.isOpen();
            config = new ConfigPanel(entries(wrap));
            if (wasOpen) {
                config.open();
            }
            laidFormY = -1;
        }
        if (formY != laidFormY || formH != laidFormH || text != laidText || button != laidButton) {
            config.layout(formY, formH, text, button);
            laidFormY = formY;
            laidFormH = formH;
            laidText = text;
            laidButton = button;
        }
        about.layout(viewW, viewH, button);
    }

    /** The score list's top row: under the bar (C {@code RadarHeight + ButtonHeight + 2}). */
    public int scoreTop() {
        return bar.top() + bar.height();
    }

    /** BEHAVIOR: true while CONFIG covers the score list (the list is not drawn then). */
    public boolean coversScores() {
        return config.isOpen();
    }

    /**
     * Fill the draw lists: the bar and CONFIG into {@code panel} (after the score list), the pulldown into
     * {@link #panelOverlay()}, the information window into {@link #aboutHud()} (view coordinates).
     */
    public void draw(HudFrame panel, TextMetrics text, TextMetrics button, int viewX) {
        panelOverlay.clear();
        aboutHud.clear();
        bar.draw(panel, panelOverlay, button);
        config.draw(panel, text, button);
        about.draw(aboutHud, text, button);
    }

    /**
     * A mouse button event at window position (x, y) (logical pixels, origin top-left); {@code viewX} is where
     * the game view starts.
     * <p>
     * BEHAVIOR: only the left button works the windows (C: any button; JXPilot leaves the others to pointer
     * control). A press goes to the information window when it is over it (view coordinates), else the bar, else
     * the open CONFIG form; the window that took it gets the release wherever the pointer is. Returns whether the
     * windows used the event (unused events are the game view's).
     */
    public boolean mouseButton(int button, int action, double x, double y, int viewX) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        int px = (int) Math.floor(x);
        int py = (int) Math.floor(y);
        if (action == GLFW_PRESS) {
            if (about.press(px - viewX, py)) {
                holder = 1;
            } else if (bar.press(px, py)) {
                holder = 2;
            } else if (config.press(px, py)) {
                holder = 3;
            } else {
                holder = 0;
                return false;
            }
            return true;
        }
        if (action != GLFW_RELEASE) {
            return holder != 0;
        }
        int h = holder;
        holder = 0;
        switch (h) {
            case 1 -> about.release();
            case 2 -> act(bar.release(px, py));
            case 3 -> act(config.release(px, py));
            default -> {
                return false;
            }
        }
        return true;
    }

    /** BEHAVIOR: pointer motion goes to the window holding the pointer; returns whether one does. */
    public boolean mouseMove(double x, double y, int viewX) {
        int px = (int) Math.floor(x);
        int py = (int) Math.floor(y);
        switch (holder) {
            case 2 -> bar.move(px, py);
            case 3 -> config.move(px, py);
            case 1 -> { }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Carry out a window action.
     * <p>
     * BEHAVIOR: QUIT quits; ABOUT opens the information window; KEYS toggles the key list; CONFIG opens the
     * panel; SCORE / PLAYER close CONFIG and switch real names off / on (C {@code Score_callback},
     * {@code Player_callback}); MOTD shows the MOTD, refreshed when refreshMotd is on; SAVE writes
     * {@link #saveList} and shows a failure on the button.
     */
    void act(UiAction a) {
        switch (a) {
            case QUIT -> host.quit();
            case ABOUT -> about.open();
            case KEYS -> host.toggleKeys();
            case CONFIG -> config.open();
            case SCORE -> {
                config.close();
                host.showRealName(false);
            }
            case PLAYER -> {
                config.close();
                host.showRealName(true);
            }
            case MOTD -> host.showMotd(options.flag(REFRESH_MOTD));
            case SAVE -> config.saved(host.save(saveList(savedName, savedBindings, savedBanks)));
            default -> { }
        }
    }

    private String savedName = "";
    private KeyBindings savedBindings;
    private String[] savedBanks = {"", "", "", ""};

    /** What "Save Configuration" saves besides the options: the player name, the key bindings, the modifier banks. */
    public void saveContext(String name, KeyBindings bindings, String[] modifierBanks) {
        savedName = name;
        savedBindings = bindings;
        savedBanks = modifierBanks.clone();
    }

    /**
     * C {@code Config_save}'s resources, in its order.
     * <p>
     * BEHAVIOR: name; the six control settings ("%.3f"); speedFactHUD, speedFactPTR; fuelNotify, fuelWarning and
     * fuelCritical as floats (C saves them with Config_save_float); then C's list of bools, ints and floats from
     * the options; maxVolume; scaleFactor and altScaleFactor; every bound key function with its keysyms
     * (C {@code Config_save_keys}); modifierBank1-4. maxFPS is not saved (C: "don't save maxFPS").
     */
    List<RcWriter.Resource> saveList(String name, KeyBindings bindings, String[] banks) {
        List<RcWriter.Resource> r = new ArrayList<>();
        r.add(new RcWriter.Resource("name", name));
        r.add(fixed("power", settings.power));
        r.add(fixed("turnSpeed", settings.turnSpeed));
        r.add(fixed("turnResistance", settings.turnResistance));
        r.add(fixed("altPower", settings.altPower));
        r.add(fixed("altTurnSpeed", settings.altTurnSpeed));
        r.add(fixed("altTurnResistance", settings.altTurnResistance));
        r.add(opt(SPEED_FACT_HUD));
        r.add(opt(SPEED_FACT_PTR));
        r.add(fixed("fuelNotify", options.integer(FUEL_NOTIFY)));
        r.add(fixed("fuelWarning", options.integer(FUEL_WARNING)));
        r.add(fixed("fuelCritical", options.integer(FUEL_CRITICAL)));
        for (ClientOption o : SAVED) {
            r.add(opt(o));
        }
        r.add(new RcWriter.Resource("maxVolume", Integer.toString(maxVolume)));
        r.add(opt(SCALE_FACTOR));
        r.add(opt(ALT_SCALE_FACTOR));
        if (bindings != null) {
            for (KeyFunctions.Fn f : KeyFunctions.all()) {
                List<String> keys = bindings.keysyms(f);
                if (!keys.isEmpty()) {
                    r.add(new RcWriter.Resource(f.resource(), String.join(" ", keys)));
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            r.add(new RcWriter.Resource("modifierBank" + (i + 1), i < banks.length ? banks[i] : ""));
        }
        return r;
    }

    /** C {@code Config_save}'s options after the fuel levels, in its order. */
    static final ClientOption[] SAVED = {
        SHOW_SHIP_NAME, SHOW_MINE_NAME, SHOW_MESSAGES, MAX_MESSAGES, MESSAGES_TO_STDOUT, OLD_MESSAGES_COLOR,
        REVERSE_SCROLL, SHOW_HUD, SHOW_HUD_RADAR, VERTICAL_HUD_LINE, HORIZONTAL_HUD_LINE, FUEL_METER, FUEL_GAUGE,
        TURN_SPEED_METER, POWER_METER, PACKET_SIZE_METER, PACKET_LOSS_METER, PACKET_DROP_METER, PACKET_LAG_METER,
        SLIDING_RADAR, SHOW_ITEMS, SHOW_ITEMS_TIME, SHOW_SCORE_DECIMALS, OUTLINE_WORLD, FILLED_WORLD, TEXTURED_WALLS,
        TEXTURED_OBJECTS, CLOCK, CLOCK_AMPM, BACKGROUND_POINT_DIST, BACKGROUND_POINT_SIZE, SPARK_SIZE, SPARK_PROB,
        SHOT_SIZE, TEAM_SHOT_SIZE, SHOW_NASTY_SHOTS, HUD_COLOR, HUD_LOCK_COLOR, WALL_COLOR, DECOR_COLOR, SHOW_DECOR,
        OUTLINE_DECOR, FILLED_DECOR, TEXTURED_DECOR, TEXTURED_BALLS, RECEIVE_WINDOW_SIZE, CHARS_PER_SECOND,
        MARKING_LIGHTS, TOGGLE_SHIELD, AUTO_SHIELD, CLIENT_PORT_START, CLIENT_PORT_END,
        SHOW_SHOT_TRACERS, // JXPilot only, after C's
    };

    private RcWriter.Resource opt(ClientOption o) {
        return new RcWriter.Resource(o.resource, options.saveText(o));
    }

    private static RcWriter.Resource fixed(String name, double v) {
        return new RcWriter.Resource(name, String.format(java.util.Locale.ROOT, "%.3f", v));
    }
}
