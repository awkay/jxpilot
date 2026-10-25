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
package com.fulcrologic.jxpilot.client.game.options;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The C client's resources ({@code src/client/default.c} {@code options[]}) other than the key bindings and
 * the ones {@code KeyConfig} keeps itself (power, turnSpeed, turnResistance and their alternates, maxVolume,
 * modifierBank1-4, shipShape, shipShapeFile), in C's order, with C's type, default, range and help text.
 * Values live in {@link ClientOptions}.
 * <p>
 * C's ranges: the {@code LIMIT}s of {@code Parse_options}, else the bounds of the option's
 * {@code Config_create_*} widget ({@code configure.c}), else none. An option with a {@link #notApplicable}
 * reason is accepted and kept but changes nothing in JXPilot (X11 or platform details, the server list, the
 * replay recorder). JXPilot's own resources follow C's, at the end.
 */
public enum ClientOption {
    NAME("name", Type.STRING, "", "Set the nickname."),
    USER("user", Type.STRING, "", "Set the realname."),
    HOST("host", Type.STRING, "", "Set the hostname."),
    JOIN("join", Type.BOOL, "Yes", "Join the game immediately, no questions asked.",
        "JXPilot always joins at once"),
    NO_LOCAL_MOTD("noLocalMotd", Type.BOOL, "No", "Do not display the local Message Of The Day."),
    AUTO_SERVER_MOTD_POPUP("autoServerMotdPopup", Type.BOOL, "Yes",
        "Automatically popup the MOTD of the server on startup."),
    REFRESH_MOTD("refreshMotd", Type.BOOL, "No",
        "Get a fresh copy of the server MOTD every time it is displayed."),
    TEXT("text", Type.BOOL, "No", "Use the simple text interface to contact a server.",
        "JXPilot has no text interface; it always joins the server it is given"),
    LIST("list", Type.BOOL, "No", "List all servers running on the local network.",
        "the LAN server list is part of the lobby (docs/LOBBY-SPEC.md)"),
    TEAM("team", Type.INT, "-1", "Set the team to join (0-9; any other value: no team)."),
    DISPLAY("display", Type.STRING, "", "Set the X display.", "X11 only"),
    KEYBOARD("keyboard", Type.STRING, "", "Set the X keyboard input.", "X11 only"),
    GEOMETRY("geometry", Type.STRING, "", "Set the window size and position in standard X geometry format."),
    IGNORE_WINDOW_MANAGER("ignoreWindowManager", Type.BOOL, "No",
        "Ignore the window manager when opening the top level player window.", "X11 only"),
    SHUTDOWN("shutdown", Type.STRING, "", "Shutdown the server with a message."),
    PORT("port", Type.INT, "15345", 1, 65535, "Set the port number of the server."),
    SHOW_HUD("showHUD", Type.BOOL, "Yes", "Should the HUD be displayed or not."),
    SHOW_HUD_RADAR("showHUDRadar", Type.BOOL, "No", "Should the HUD radar be displayed or not."),
    FUEL_NOTIFY("fuelNotify", Type.INT, "500", 0, 1000, "The limit when the HUD fuel bar will become visible."),
    FUEL_WARNING("fuelWarning", Type.INT, "200", 0, 1000, "The limit when the HUD fuel bar will start flashing."),
    FUEL_CRITICAL("fuelCritical", Type.INT, "100", 0, 1000, "The limit when the HUD fuel bar will flash faster."),
    VERTICAL_HUD_LINE("verticalHUDLine", Type.BOOL, "No", "Should the vertical lines in the HUD be drawn."),
    HORIZONTAL_HUD_LINE("horizontalHUDLine", Type.BOOL, "Yes", "Should the horizontal lines in the HUD be drawn."),
    SPEED_FACT_HUD("speedFactHUD", Type.FLOAT, "0.0", -10, 10,
        "Should the HUD be moved, to indicate the current velocity?"),
    SPEED_FACT_PTR("speedFactPTR", Type.FLOAT, "0.0", -10, 10,
        "Uses a red line to indicate the current velocity and direction."),
    FUEL_METER("fuelMeter", Type.BOOL, "No", "Determines if the fuel meter should be visible."),
    FUEL_GAUGE("fuelGauge", Type.BOOL, "Yes", "Determines if the fuel gauge should be visible."),
    TURN_SPEED_METER("turnSpeedMeter", Type.BOOL, "No", "Should the turn speed meter be visible at all times."),
    POWER_METER("powerMeter", Type.BOOL, "No", "Should the power meter be visible at all times."),
    PACKET_SIZE_METER("packetSizeMeter", Type.BOOL, "No",
        "Should the network packet size meter be visible at all times."),
    PACKET_LOSS_METER("packetLossMeter", Type.BOOL, "No", "Should the packet loss meter be visible."),
    PACKET_DROP_METER("packetDropMeter", Type.BOOL, "No", "Should the packet drop meter be visible."),
    PACKET_LAG_METER("packetLagMeter", Type.BOOL, "No", "Should the packet lag meter be visible."),
    SLIDING_RADAR("slidingRadar", Type.BOOL, "No",
        "If the game is in edgewrap mode then the radar will keep your position on the radar in the center."),
    OUTLINE_WORLD("outlineWorld", Type.BOOL, "No", "Draws only the outline of all the blue map constructs."),
    FILLED_WORLD("filledWorld", Type.BOOL, "No", "Draws the walls solid, filled with one color."),
    TEXTURED_WALLS("texturedWalls", Type.BOOL, "No", "Draws the walls filled with a texture pattern."),
    WALL_TEXTURE_FILE("wallTextureFile", Type.STRING, "",
        "Specify a XPM format pixmap file to load the wall texture from."),
    TEXTURE_PATH("texturePath", Type.STRING, "", "Search path for texture files."),
    TEXTURED_OBJECTS("texturedObjects", Type.BOOL, "No", "Whether to draw ships, shots and walls with textures."),
    MARKING_LIGHTS("markingLights", Type.BOOL, "Yes", "Should the fighters have marking lights, just like airplanes?"),
    SPARK_PROB("sparkProb", Type.FLOAT, "0.50", 0, 1, "The chance that sparks are drawn or not."),
    SPARK_SIZE("sparkSize", Type.INT, "2", 1, 8, "Size of sparks in pixels."),
    CHARS_PER_SECOND("charsPerSecond", Type.INT, "50", 10, 255,
        "Speed in which messages appear on screen in characters per second."),
    CLOCK("clock", Type.BOOL, "No", "Should a clock be displayed in the top right of the score window."),
    CLOCK_AMPM("clockAMPM", Type.BOOL, "No", "12 or 24 hour format for clock display."),
    POINTER_CONTROL("pointerControl", Type.BOOL, "No", "Enable mouse control."),
    GAME_FONT("gameFont", Type.STRING, "-*-times-*-*-*--18-*-*-*-*-*-iso8859-1",
        "The font used on the HUD and for most other text."),
    SCORE_LIST_FONT("scoreListFont", Type.STRING, "-*-fixed-bold-*-*--13-*-*-*-c-*-iso8859-1",
        "The font used on the score list."),
    BUTTON_FONT("buttonFont", Type.STRING, "-*-*-bold-o-*--14-*-*-*-*-*-iso8859-1", "The font used on all buttons."),
    TEXT_FONT("textFont", Type.STRING, "-*-*-bold-i-*--14-*-*-*-p-*-iso8859-1",
        "The font used in the help and about windows."),
    TALK_FONT("talkFont", Type.STRING, "-*-fixed-bold-*-*--15-*-*-*-c-*-iso8859-1",
        "The font used in the talk window."),
    MOTD_FONT("motdFont", Type.STRING, "-*-courier-bold-r-*--14-*-*-*-*-*-iso8859-1",
        "The font used in the MOTD window."),
    MESSAGE_FONT("messageFont", Type.STRING, "-*-times-*-*-*--14-*-*-*-*-*-iso8859-1",
        "The font used for drawing messages."),
    MAX_MESSAGES("maxMessages", Type.INT, "8", 1, 15, "The maximum number of messages to display."),
    MESSAGES_TO_STDOUT("messagesToStdout", Type.INT, "0", 0, 2, "Send messages to standard output."),
    REVERSE_SCROLL("reverseScroll", Type.BOOL, "No", "Reverse scroll direction of messages."),
    SELECTION_AND_HISTORY("selectionAndHistory", Type.BOOL, "Yes",
        "Provide cut&paste for the player messages and the talk window and a history of sent messages."),
    MAX_LINES_IN_HISTORY("maxLinesInHistory", Type.INT, "32", 1, 128,
        "Number of your messages saved in the `history' of the talk window."),
    SHOT_SIZE("shotSize", Type.INT, "3", 1, 8, "The size of shots in pixels."),
    TEAM_SHOT_SIZE("teamShotSize", Type.INT, "2", 1, 8, "The size of team shots in pixels.",
        "C 4.5.5 never uses it: team shots are drawn with shotSize (guiobjects.c Gui_paint_teamshot)"),
    SHOW_NASTY_SHOTS("showNastyShots", Type.BOOL, "No",
        "Use the new Nasty Looking Shots or the original rectangle shots."),
    BACKGROUND_POINT_DIST("backgroundPointDist", Type.INT, "8", 0, 10,
        "The distance between points in the background measured in blocks."),
    BACKGROUND_POINT_SIZE("backgroundPointSize", Type.INT, "2", 0, 8,
        "Specifies the size of the background points.  0 means no points."),
    TITLE_FLIP("titleFlip", Type.BOOL, "Yes", "Should the title bar change or not."),
    TOGGLE_SHIELD("toggleShield", Type.BOOL, "No", "Are shields toggled by a keypress only?"),
    AUTO_SHIELD("autoShield", Type.BOOL, "Yes", "Are shields lowered automatically for weapon fire?"),
    SHIELD_DRAW_SOLID("shieldDrawSolid", Type.STRING, "Default", "Are shields drawn in a solid line.",
        "C 4.5.5 computes shieldDrawMode but never uses it: shields are always dashed (guiobjects.c Gui_paint_ship)"),
    SHOW_SHIP_NAME("showShipName", Type.BOOL, "Yes", "Should all ships have the name of the player drawn below them."),
    SHOW_MINE_NAME("showMineName", Type.BOOL, "Yes",
        "Should the name of the owner of the mine be drawn below the mine."),
    SHOW_MESSAGES("showMessages", Type.BOOL, "Yes", "Should messages appear on screen."),
    SHOW_ITEMS("showItems", Type.BOOL, "Yes", "Should owned items be displayed permanently on the HUD."),
    SHOW_ITEMS_TIME("showItemsTime", Type.FLOAT, "2.0", 0, 10,
        "The time in seconds to display item information when it changes."),
    SHOW_SCORE_DECIMALS("showScoreDecimals", Type.INT, "1", 0, 2,
        "The number of decimals to use when displaying scores."),
    RECEIVE_WINDOW_SIZE("receiveWindowSize", Type.INT, "3", 1, 4, "Too complicated.  Keep it on 3.",
        "JXPilot receives every packet without a window"),
    VISUAL("visual", Type.STRING, "", "Specify which visual to use for allocating colors.", "X11 only"),
    MONO("mono", Type.BOOL, "No", "Only use black and white.", "X11 only"),
    ERASE("erase", Type.BOOL, "No", "Use Erase(-hack) for slow X.", "X11 only"),
    COLOR_SWITCH("colorSwitch", Type.BOOL, "Yes", "Use color buffering or not.", "X11 only (OpenGL double buffers)"),
    MULTIBUFFER("multibuffer", Type.BOOL, "No", "Use the X windows multibuffer extension if present.",
        "X11 only (OpenGL double buffers)"),
    MAX_COLORS("maxColors", Type.INT, "8", 4, 16, "The number of colors to use.  Valid values are 4, 8 and 16."),
    BLACK("black", Type.STRING, "", "The color value for black (better use color0 instead)."),
    WHITE("white", Type.STRING, "", "The color value for white (better use color1 instead)."),
    BLUE("blue", Type.STRING, "", "The color value for blue (better use color2 instead)."),
    RED("red", Type.STRING, "", "The color value for red (better use color3 instead)."),
    COLOR0("color0", Type.STRING, "", "The color value for the first color."),
    COLOR1("color1", Type.STRING, "", "The color value for the second color."),
    COLOR2("color2", Type.STRING, "", "The color value for the third color."),
    COLOR3("color3", Type.STRING, "", "The color value for the fourth color."),
    COLOR4("color4", Type.STRING, "", "The color value for the fifth color."),
    COLOR5("color5", Type.STRING, "", "The color value for the sixth color."),
    COLOR6("color6", Type.STRING, "", "The color value for the seventh color."),
    COLOR7("color7", Type.STRING, "", "The color value for the eighth color."),
    COLOR8("color8", Type.STRING, "", "The color value for the nineth color."),
    COLOR9("color9", Type.STRING, "", "The color value for the tenth color."),
    COLOR10("color10", Type.STRING, "", "The color value for the eleventh color."),
    COLOR11("color11", Type.STRING, "", "The color value for the twelfth color."),
    COLOR12("color12", Type.STRING, "", "The color value for the thirteenth color."),
    COLOR13("color13", Type.STRING, "", "The color value for the fourteenth color."),
    COLOR14("color14", Type.STRING, "", "The color value for the fifteenth color."),
    COLOR15("color15", Type.STRING, "", "The color value for the sixteenth color."),
    HUD_COLOR("hudColor", Type.INT, "4", 0, 15, "Which color number to use for drawing the HUD."),
    HUD_LOCK_COLOR("hudLockColor", Type.INT, "4", 0, 15, "Which color number to use for drawing the lock on the HUD."),
    WALL_COLOR("wallColor", Type.INT, "2", 0, 15, "Which color number to use for drawing walls."),
    WALL_RADAR_COLOR("wallRadarColor", Type.INT, "2", 0, 15,
        "Which color number to use for drawing walls on the radar."),
    DECOR_COLOR("decorColor", Type.INT, "6", 0, 15, "Which color number to use for drawing decorations."),
    SHOW_DECOR("showDecor", Type.BOOL, "Yes", "Should decorations be displayed on the screen and radar?"),
    DECOR_RADAR_COLOR("decorRadarColor", Type.INT, "6", 0, 15,
        "Which color number to use for drawing decorations on the radar."),
    OLD_MESSAGES_COLOR("oldMessagesColor", Type.INT, "1", 0, 15, "Which color number to use for drawing old messages."),
    OUTLINE_DECOR("outlineDecor", Type.BOOL, "No", "Draws only the outline of the map decoration."),
    FILLED_DECOR("filledDecor", Type.BOOL, "No", "Draws filled decorations."),
    TEXTURED_DECOR("texturedDecor", Type.BOOL, "No", "Draws the map decoration filled with a texture pattern."),
    DECOR_TEXTURE_FILE("decorTextureFile", Type.STRING, "",
        "Specify a XPM format pixmap file to load the decor texture from."),
    TEXTURED_BALLS("texturedBalls", Type.BOOL, "No",
        "Draw the balls with a texture specified by the ballTextureFile option."),
    BALL_TEXTURE_FILE("ballTextureFile", Type.STRING, "",
        "Specify a XPM format pixmap file to load the ball texture from."),
    TARGET_RADAR_COLOR("targetRadarColor", Type.INT, "4", 0, 15,
        "Which color number to use for drawing targets on the radar."),
    SPARK_COLORS("sparkColors", Type.STRING, "5,6,7,3", "Which color numbers to use for spark and debris particles."),
    POINTER_BUTTON1("pointerButton1", Type.STRING, "keyFireShot",
        "The key to activate when pressing the first mouse button."),
    POINTER_BUTTON2("pointerButton2", Type.STRING, "keyThrust",
        "The key to activate when pressing the second mouse button."),
    POINTER_BUTTON3("pointerButton3", Type.STRING, "keyShield",
        "The key to activate when pressing the third mouse button."),
    POINTER_BUTTON4("pointerButton4", Type.STRING, "", "The key to activate when pressing the fourth mouse button."),
    POINTER_BUTTON5("pointerButton5", Type.STRING, "", "The key to activate when pressing the fifth mouse button."),
    MAX_FPS("maxFPS", Type.INT, "20", 0, 255, "Set client's maximum FPS supported."),
    RECORD_FILE("recordFile", Type.STRING, "", "An optional file where a recording of a game can be made.",
        "the replay recorder is excluded from JXPilot"),
    CLIENT_PORT_START("clientPortStart", Type.INT, "0", 0, 65535,
        "Use UDP ports clientPortStart - clientPortEnd (for firewalls)."),
    CLIENT_PORT_END("clientPortEnd", Type.INT, "0", 0, 65535,
        "Use UDP ports clientPortStart - clientPortEnd (for firewalls)."),
    THREADED_DRAW("threadedDraw", Type.BOOL, "No", "Tell Windows to do the heavy BitBlt in another thread.",
        "Windows only"),
    RADAR_DIVISOR("radarDivisor", Type.INT, "1", 1, 100, "Specifies how many frames between radar window updates."),
    SCALE_FACTOR("scaleFactor", Type.FLOAT, "1.0", 0.2, 8, "Specifies scaling factor for the drawing window."),
    ALT_SCALE_FACTOR("altScaleFactor", Type.FLOAT, "2.0", 0.2, 8,
        "Specifies alternative scaling factor for the drawing window."),
    SOUNDS("sounds", Type.STRING, "", "Specifies the sound file.",
        "JXPilot plays its bundled clips (docs/SOUND-PLAN.md)"),
    AUDIO_SERVER("audioServer", Type.STRING, "", "Specifies the audio server to use.",
        "JXPilot plays through OpenAL"),
    FRAME_BUFFER("frameBuffer", Type.STRING, "/dev/fb", "Specifies the device name of the frame buffer.",
        "SPARC colour map hack only"),
    TEST("test", Type.STRING, "", "Which development testing parameters to use?", "C development switch"),
    MSG1("msg1", Type.STRING, "", "Talkmessage 1."),
    MSG2("msg2", Type.STRING, "", "Talkmessage 2."),
    MSG3("msg3", Type.STRING, "", "Talkmessage 3."),
    MSG4("msg4", Type.STRING, "", "Talkmessage 4."),
    MSG5("msg5", Type.STRING, "", "Talkmessage 5."),
    MSG6("msg6", Type.STRING, "", "Talkmessage 6."),
    MSG7("msg7", Type.STRING, "", "Talkmessage 7."),
    MSG8("msg8", Type.STRING, "", "Talkmessage 8."),
    MSG9("msg9", Type.STRING, "", "Talkmessage 9."),
    MSG10("msg10", Type.STRING, "", "Talkmessage 10."),
    MSG11("msg11", Type.STRING, "", "Talkmessage 11."),
    MSG12("msg12", Type.STRING, "", "Talkmessage 12."),
    MSG13("msg13", Type.STRING, "", "Talkmessage 13."),
    MSG14("msg14", Type.STRING, "", "Talkmessage 14."),
    MSG15("msg15", Type.STRING, "", "Talkmessage 15."),
    MSG16("msg16", Type.STRING, "", "Talkmessage 16."),
    MSG17("msg17", Type.STRING, "", "Talkmessage 17."),
    MSG18("msg18", Type.STRING, "", "Talkmessage 18."),
    MSG19("msg19", Type.STRING, "", "Talkmessage 19."),
    MSG20("msg20", Type.STRING, "", "Talkmessage 20."),
    // JXPilot's own resources (not in C)
    SHOW_SHOT_TRACERS("showShotTracers", Type.BOOL, "Yes",
        "JXPilot only: with prediction on, draw a short fading line from a new bullet's source to where it is drawn.");

    /** Value types (C {@code Get_bool_resource}, {@code Get_int_resource}, {@code Get_float_resource}, string). */
    public enum Type { BOOL, INT, FLOAT, STRING }

    /** The X resource name. */
    public final String resource;
    public final Type type;
    /** C's default, as resource text. */
    public final String defaultText;
    /** Range for INT and FLOAT (both 0 for BOOL and STRING). */
    public final double min;
    public final double max;
    /** C's help text (first sentence). */
    public final String help;
    /** Why the option has no effect in JXPilot, or null when it is used. */
    public final String notApplicable;

    ClientOption(String resource, Type type, String defaultText, String help) {
        this(resource, type, defaultText, 0, 0, help, null);
    }

    ClientOption(String resource, Type type, String defaultText, String help, String notApplicable) {
        this(resource, type, defaultText, 0, 0, help, notApplicable);
    }

    ClientOption(String resource, Type type, String defaultText, double min, double max, String help) {
        this(resource, type, defaultText, min, max, help, null);
    }

    ClientOption(String resource, Type type, String defaultText, int min, int max, String help, String notApplicable) {
        this(resource, type, defaultText, (double) min, (double) max, help, notApplicable);
    }

    ClientOption(String resource, Type type, String defaultText, double min, double max, String help,
                 String notApplicable) {
        this.resource = resource;
        this.type = type;
        this.defaultText = defaultText;
        this.min = min;
        this.max = max;
        this.help = help;
        this.notApplicable = notApplicable;
    }

    private static final Map<String, ClientOption> BY_NAME = new HashMap<>();

    static {
        for (ClientOption o : values()) {
            BY_NAME.put(o.resource.toLowerCase(Locale.ROOT), o);
        }
    }

    /** BEHAVIOR: the option with this resource name, ignoring case (X resources match exactly; C's is lenient), or null. */
    public static ClientOption byResource(String name) {
        return BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** BEHAVIOR: msg1..msg20 for 1..20 (talk macros); IllegalArgumentException otherwise. */
    public static ClientOption message(int n) {
        if (n < 1 || n > 20) {
            throw new IllegalArgumentException("talk message " + n);
        }
        return values()[MSG1.ordinal() + n - 1];
    }

    /** BEHAVIOR: color0..color15 for 0..15; IllegalArgumentException otherwise. */
    public static ClientOption color(int n) {
        if (n < 0 || n > 15) {
            throw new IllegalArgumentException("colour " + n);
        }
        return values()[COLOR0.ordinal() + n];
    }

    /** BEHAVIOR: pointerButton1..5 for 1..5; IllegalArgumentException otherwise. */
    public static ClientOption pointerButton(int n) {
        if (n < 1 || n > 5) {
            throw new IllegalArgumentException("pointer button " + n);
        }
        return values()[POINTER_BUTTON1.ordinal() + n - 1];
    }
}
