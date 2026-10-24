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
package com.fulcrologic.jxpilot.dist.docs;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionType;
import com.fulcrologic.jxpilot.client.game.input.ControlSettings;
import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.game.input.KeyFunctions;
import com.fulcrologic.jxpilot.common.net.ControlKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Generates the AsciiDoc reference tables of the user guide ({@code docs/guide/generated/}) from the
 * code: every server option from {@link Opt} and the default key bindings from {@link ControlKey}.
 * {@link GeneratedDocsTest} fails the build when the committed files differ from this output.
 */
public final class GuideTables {
    private GuideTables() {}

    /** An option category: anchor id, title, intro sentence and which options belong to it. */
    record Category(String id, String title, String intro, Predicate<Opt> member) {}

    private static Set<String> names(String... n) {
        return Set.of(n);
    }

    private static Predicate<Opt> named(Set<String> names) {
        return o -> names.contains(o.optionName());
    }

    private static Predicate<Opt> prefix(String... prefixes) {
        return o -> {
            for (String p : prefixes) {
                if (o.optionName().startsWith(p)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static Predicate<Opt> suffix(String... suffixes) {
        return o -> {
            for (String s : suffixes) {
                if (o.optionName().endsWith(s)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static <T> Predicate<T> pred(Predicate<T> p) {
        return p;
    }

    /** {@code maxTanks} and the like: a {@code max...} option whose {@code initial...} twin exists. */
    private static boolean itemLimit(Opt o) {
        String n = o.optionName();
        return n.startsWith("max") && Opt.find("initial" + n.substring(3)) != null;
    }

    /**
     * Categories in table order. An option goes to the FIRST category whose predicate accepts it, so
     * the order below matters; anything unmatched lands in "Other options". Names that exist only on
     * unmerged branches are harmless here and place those options once they arrive.
     */
    static final List<Category> CATEGORIES = List.of(
        new Category("opts-files", "Command line and server files",
            "Switches of the server process itself and the files it reads.",
            named(names("help", "version", "dump", "expand", "defaultsFileName", "passwordFileName", "motdFileName",
                "scoreTableFileName", "adminMessageFileName", "adminMessageFileSizeLimit", "mapFileName"))),
        new Category("opts-network", "Server and network",
            "Where and how the server listens, who may connect and how much traffic it accepts.",
            named(names("contactPort", "serverHost", "bind", "bindWait", "snapshotDivisor", "radarDivisor",
                "ipPacketRate", "ipPacketBurst", "sessionPacketRate", "sessionPacketBurst", "reportToMetaServer",
                "searchDomainForXPilot", "denyHosts", "maxClientsPerIP", "clientPortStart", "clientPortEnd", "password",
                "idleRun", "noQuit", "pLockServer", "timerResolution", "ignore20MaxFPS", "maxPauseTime"))),
        new Category("opts-race", "Races, rounds and game length",
            "Race mode (checkpoints), ball races, rounds and time limits.",
            named(names("timing", "ballrace", "ballraceConnected", "checkpointRadius", "raceLaps", "gameDuration",
                "roundDelay", "maxRoundTime", "roundsToPlay"))),
        new Category("opts-radar", "Radar", "What the radar shows.", suffix("OnRadar")),
        new Category("opts-scoring", "Scoring", "Points for kills, deaths and destroying things.",
            suffix("ScoreMult", "Points", "MaxScore")),
        new Category("opts-items", "Items and fuel",
            "Item appearance probabilities, starting amounts and carrying limits.",
            prefix("item", "initial").or(GuideTables::itemLimit).or(named(names("movingItemProb", "randomItemProb",
                "dropItemOnKillProb", "detonateItemOnKillProb", "destroyItemInCollisionProb", "asteroidItemProb",
                "asteroidMaxItems", "loseItemDestroys", "shieldedItemPickup", "cannonItemProbMult", "maxItemDensity",
                "maxOffensiveItems", "maxDefensiveItems")))),
        new Category("opts-walls", "Walls, crashes and bouncing",
            "What happens when ships and objects hit walls, the world edge or each other.",
            GuideTables.<Opt>pred(o -> o.optionName().toLowerCase(Locale.ROOT).contains("bounce"))
                .or(named(names("allowPlayerCrashes")))),
        new Category("opts-map", "Map and world", "The map itself, its size, edges and visible map features.",
            prefix("map").or(named(names("edgeWrap", "extraBorder", "framesPerSecond", "wormholeVisible", "wormTime",
                "itemConcentratorVisible", "asteroidConcentratorVisible")))),
        new Category("opts-physics", "Physics and gravity", "Gravity, masses and friction.",
            prefix("gravity").or(named(names("shipMass", "ballMass", "shotMass", "minItemMass", "friction",
                "blockFriction", "blockFrictionVisible", "coriolis", "turnThrust")))),
        new Category("opts-teams", "Teams and alliances", "Team play and player alliances.",
            prefix("team").or(named(names("allowAlliances", "announceAlliances")))),
        new Category("opts-cannons", "Cannons", "Map cannons.", prefix("cannon")),
        new Category("opts-robots", "Robots", "Computer-controlled players and detached fuel tanks.",
            prefix("robot").or(named(names("maxRobots", "minRobots", "restrictRobots", "reserveRobotTeam",
                "logRobots", "tankRealName", "tankHostName", "tankScoreDecrement")))),
        new Category("opts-objectives", "Treasures, balls and targets",
            "Capture-the-ball play, ball connectors and destroyable targets.",
            prefix("target", "treasure", "ball").or(named(names("captureTheFlag", "connectorIsString",
                "maxBallConnectorRatio")))),
        new Category("opts-weapons", "Shots and weapons", "Bullets, missiles, mines, nukes, lasers and ECMs.",
            prefix("shot", "nuke", "mine", "rogue", "ecms").or(named(names("maxPlayerShots", "fireRepeatRate",
                "keepShots", "allowSmartMissiles", "allowHeatSeekers", "allowTorpedoes", "allowNukes", "allowClusters",
                "allowModifiers", "allowLaserModifiers", "distinguishMissiles", "maxMissilesPerPack", "maxMinesPerPack",
                "identifyMines", "shieldedMining", "laserIsStunGun", "minMineSpeed", "missileLife", "baseMineRange")))),
        new Category("opts-ships", "Ships and players",
            "Ship shapes, shields, lives, visibility and other per-player rules.",
            named(names("defaultShipShape", "tankShipShape", "allowShipShapes", "allowShields", "playerStartsShielded",
                "allowPlayerKilling", "selfImmunity", "limitedVisibility", "minVisibilityDistance",
                "maxVisibilityDistance", "limitedLives", "worldLives", "reset", "resetOnHuman", "maxVisibleObject",
                "allowViewing", "lockOtherTeam", "cloakedExhaust", "cloakedShield", "useWreckage",
                "wreckageCollisionMayKill"))),
        new Category("opts-asteroids", "Asteroids", "Asteroid appearance and behaviour.",
            prefix("asteroid").or(named(names("maxAsteroidDensity")))),
        new Category("opts-other", "Other options", "Options not in any category above.", o -> true));

    /** BEHAVIOR: groups every option under its first matching category, keeping {@link Opt} order. */
    static Map<Category, List<Opt>> grouped() {
        Map<Category, List<Opt>> out = new LinkedHashMap<>();
        for (Category c : CATEGORIES) {
            out.put(c, new ArrayList<>());
        }
        for (Opt o : Opt.values()) {
            for (Category c : CATEGORIES) {
                if (c.member().test(o)) {
                    out.get(c).add(o);
                    break;
                }
            }
        }
        return out;
    }

    /** BEHAVIOR: user-facing name of a value type, with how to write it. */
    static String typeName(OptionType t) {
        return switch (t) {
            case VOID -> "switch (no value)";
            case INT -> "integer";
            case REAL -> "real";
            case BOOL -> "boolean";
            case IPOS -> "position `x,y`";
            case STRING -> "text";
            case SEC -> "seconds";
            case PER_SEC -> "per second";
            case LIST -> "comma list";
        };
    }

    /** BEHAVIOR: lists the sources an option may be set from, in precedence order. */
    static String origins(Opt o) {
        List<String> w = new ArrayList<>();
        if ((o.flags() & Opt.F.CMD) != 0) {
            w.add("command line");
        }
        if ((o.flags() & Opt.F.MAP) != 0) {
            w.add("map");
        }
        if ((o.flags() & Opt.F.DEF) != 0) {
            w.add("config file");
        }
        if ((o.flags() & Opt.F.PWD) != 0) {
            w.add("password file");
        }
        return w.isEmpty() ? "-" : String.join(", ", w);
    }

    /** Literal (no AsciiDoc formatting) monospace text. */
    static String literal(String s) {
        if (s.indexOf('+') < 0 && s.indexOf('`') < 0) {
            return "`+" + s + "+`";
        }
        return "`pass:c[" + s.replace("]", "\\]") + "]`";
    }

    /** Table-cell text: escapes the cell separator and attribute references. */
    static String cell(String s) {
        return s.replace("|", "\\|").replace("{", "\\{");
    }

    /** BEHAVIOR: switches show "-"; null reads (none), empty reads (empty), anything else is a literal. */
    static String defaultText(Opt o) {
        if (o.type() == OptionType.VOID) {
            return "-";
        }
        String d = o.defaultValue();
        if (d == null) {
            return "_(none)_";
        }
        if (d.isEmpty()) {
            return "_(empty)_";
        }
        return literal(d);
    }

    static String anchor(Opt o) {
        return "opt-" + o.optionName();
    }

    /** The complete {@code options.adoc}. */
    public static String optionsAdoc() {
        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATED from common/src/main/java/com/fulcrologic/jxpilot/common/config/Opt.java\n")
            .append("// by dist/src/test/java/com/fulcrologic/jxpilot/dist/docs/GuideTables.java. Do not edit:\n")
            .append("// run `make -C docs/guide generate` instead. The build fails when this file is stale.\n\n");
        Map<Category, List<Opt>> groups = grouped();
        sb.append("The server knows *").append(Opt.values().length).append("* options, in these groups:\n\n");
        for (Map.Entry<Category, List<Opt>> e : groups.entrySet()) {
            if (!e.getValue().isEmpty()) {
                sb.append("* <<").append(e.getKey().id()).append(',').append(e.getKey().title()).append(">> (")
                    .append(e.getValue().size()).append(")\n");
            }
        }
        sb.append('\n');
        for (Map.Entry<Category, List<Opt>> e : groups.entrySet()) {
            List<Opt> opts = e.getValue();
            if (opts.isEmpty()) {
                continue;
            }
            Category c = e.getKey();
            sb.append("[[").append(c.id()).append("]]\n== ").append(c.title()).append("\n\n")
                .append(c.intro()).append("\n\n")
                .append("[cols=\"3,2,2,3,2,7\",options=\"header\"]\n|===\n")
                .append("|Option |Short name |Type |Default |Set from |Description\n\n");
            for (Opt o : opts) {
                sb.append("|[[").append(anchor(o)).append("]]").append(literal(o.optionName())).append('\n');
                sb.append('|').append(o.alias().equals(o.optionName()) ? "" : literal(o.alias())).append('\n');
                sb.append('|').append(typeName(o.type())).append('\n');
                sb.append('|').append(defaultText(o)).append('\n');
                sb.append('|').append(origins(o)).append('\n');
                sb.append('|').append(cell(o.help())).append("\n\n");
            }
            sb.append("|===\n\n");
        }
        sb.append("[[opts-index]]\n== Alphabetical index\n\n");
        List<Opt> sorted = new ArrayList<>(List.of(Opt.values()));
        sorted.sort(Comparator.comparing(o -> o.optionName().toLowerCase(Locale.ROOT)));
        for (int i = 0; i < sorted.size(); i++) {
            Opt o = sorted.get(i);
            sb.append("<<").append(anchor(o)).append(',').append(o.optionName()).append(">>")
                .append(i + 1 < sorted.size() ? " ·\n" : "\n");
        }
        return sb.toString();
    }

    /** Friendly labels for X11 keysyms whose names are not obvious. */
    private static final Map<String, String> KEY_LABELS = Map.ofEntries(
        Map.entry("Next", "Page Down"), Map.entry("Prior", "Page Up"), Map.entry("Select", "Select"),
        Map.entry("space", "Space"), Map.entry("Return", "Return/Enter"), Map.entry("Linefeed", "Linefeed"),
        Map.entry("backslash", "Backslash"), Map.entry("quoteright", "' apostrophe"), Map.entry("quoteleft", "` backtick"),
        Map.entry("semicolon", ";"), Map.entry("bracketright", "]"), Map.entry("bracketleft", "["),
        Map.entry("comma", ","), Map.entry("period", "."), Map.entry("slash", "/"), Map.entry("equal", "="),
        Map.entry("KP_Delete", "Keypad Delete"), Map.entry("KP_Decimal", "Keypad ."),
        Map.entry("KP_Multiply", "Keypad *"), Map.entry("KP_Divide", "Keypad /"), Map.entry("KP_Add", "Keypad +"),
        Map.entry("KP_Subtract", "Keypad -"), Map.entry("KP_7", "Keypad 7"), Map.entry("KP_0", "Keypad 0"),
        Map.entry("KP_Insert", "Keypad Insert"), Map.entry("Shift_L", "Left Shift"),
        Map.entry("Shift_R", "Right Shift"), Map.entry("Control_L", "Left Ctrl"), Map.entry("Control_R", "Right Ctrl"),
        Map.entry("Caps_Lock", "Caps Lock"), Map.entry("Escape", "Esc"), Map.entry("BackSpace", "Backspace"),
        Map.entry("minus", "-"), Map.entry("grave", "` backtick"), Map.entry("Alt_L", "Left Alt / Option"),
        Map.entry("Alt_R", "Right Alt / Option"), Map.entry("KP_Enter", "Keypad Enter"), Map.entry("KP_8", "Keypad 8"),
        Map.entry("KP_9", "Keypad 9"), Map.entry("KP_5", "Keypad 5"));

    /** BEHAVIOR: "LOCK_NEXT_CLOSE" reads "Lock next close"; ECM stays upper case. */
    static String functionName(ControlKey k) {
        String s = k.name().toLowerCase(Locale.ROOT).replace('_', ' ').replace("ecm", "ECM");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** BEHAVIOR: each keysym as a literal, followed by its friendly label when it has one; "-" when none. */
    static String keysText(String keysyms) {
        String t = keysyms.trim();
        if (t.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (String k : t.split("\\s+")) {
            String label = KEY_LABELS.get(k);
            parts.add(label == null || label.equals(k) ? literal(k)
                : literal(k) + " (pass:c[" + label.replace("]", "\\]") + "])");
        }
        return String.join(", ", parts);
    }

    /**
     * The complete {@code keys.adoc}: every bindable key function ({@link KeyFunctions}, C's {@code keyXxx}
     * resources) with JXPilot's effective default keys ({@link KeyConfig#defaults}), what it does, and
     * where JXPilot's default differs from XPilot's; then the default control settings.
     */
    public static String keysAdoc() {
        KeyConfig cfg = KeyConfig.defaults(null);
        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATED from client/src/main/java/com/fulcrologic/jxpilot/client/game/input/KeyFunctions.java\n")
            .append("// and KeyConfig.java by dist/src/test/java/com/fulcrologic/jxpilot/dist/docs/GuideTables.java.\n")
            .append("// Do not edit: run `make -C docs/guide generate` instead. The build fails when this file is stale.\n\n")
            .append("[cols=\"3,3,5\",options=\"header\"]\n|===\n|Resource |Default keys (X11 keysym) |What it does\n\n");
        for (KeyFunctions.Fn f : KeyFunctions.all()) {
            String effective = String.join(" ", cfg.bindings().keysyms(f));
            String xpilot = xpilotDefault(f);
            StringBuilder what = new StringBuilder(cell(f.help()));
            if (f.action() != null && !f.action().implemented()) {
                what.append(" _Not in JXPilot._");
            }
            if (!sameKeys(effective, xpilot)) {
                what.append(" _XPilot's default: ").append(xpilot.isBlank() ? "none" : keysText(xpilot)).append("._");
            }
            sb.append('|').append(literal(f.resource())).append('\n')
                .append('|').append(keysText(effective)).append('\n')
                .append('|').append(what).append("\n\n");
        }
        sb.append("|===\n\n");
        ControlSettings c = cfg.settings();
        sb.append("Default control settings: power ").append(num(c.power)).append(", turnSpeed ")
            .append(num(c.turnSpeed)).append(", turnResistance ").append(num(c.turnResistance)).append("; alternates altPower ")
            .append(num(c.altPower)).append(", altTurnSpeed ").append(num(c.altTurnSpeed)).append(", altTurnResistance ")
            .append(num(c.altTurnResistance)).append(".\n");
        return sb.toString();
    }

    /**
     * BEHAVIOR: XPilot's own default keys of a function (C default.c): the ControlKey default for game keys;
     * for client actions JXPilot's table, except the two JXPilot-only functions (none in XPilot) and
     * keySendMsg1 (F1 in XPilot, which JXPilot gives to the key help).
     */
    static String xpilotDefault(KeyFunctions.Fn f) {
        if (f.key() != null) {
            return f.key().defaultKeys;
        }
        return switch (f.action()) {
            case HELP, TOGGLE_SOUND -> "";
            case MSG_1 -> "F1";
            default -> f.action().defaultKeys;
        };
    }

    /** BEHAVIOR: the same set of keysyms, ignoring order and spacing. */
    static boolean sameKeys(String a, String b) {
        java.util.Set<String> x = new java.util.TreeSet<>(java.util.Arrays.asList(a.trim().split("\\s+")));
        java.util.Set<String> y = new java.util.TreeSet<>(java.util.Arrays.asList(b.trim().split("\\s+")));
        x.remove("");
        y.remove("");
        return x.equals(y);
    }

    static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
