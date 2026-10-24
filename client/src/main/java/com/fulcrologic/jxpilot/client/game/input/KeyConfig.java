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

import com.fulcrologic.jxpilot.client.sound.SoundPlayer;
import com.fulcrologic.jxpilot.common.Const;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * The effective key bindings and control settings: C's defaults, the JXPilot supplement, then a
 * {@code ~/.xpilotrc}-format file (per-function override, empty value = unbound), then the
 * {@code --swap-scale-key} flag. Collects every problem as a {@code source:line: message} string and the
 * keys bound to more than one function ({@link #conflicts()}), and renders the binding table
 * ({@link #listing()}, {@link #helpLines()}). Pure: the caller reads the file.
 * <p>
 * The JXPilot supplement (added to C's defaults on all platforms; it overlaps nothing C binds): Option/Alt
 * (left and right) is also the connector, so a Mac laptop's single Control key can stay refuel; {@code 0} is
 * also self-destruct (C's End needs fn on a Mac laptop). Escape always quits JXPilot, so it is never bound.
 */
public final class KeyConfig {
    /** Keysyms added to C's defaults: resource, keysyms. */
    public static final String[][] SUPPLEMENT = {
        {"keyConnector", "Alt_L Alt_R"},
        {"keySelfDestruct", "0"},
    };

    private final KeyBindings bindings;
    private final ControlSettings settings = new ControlSettings();
    /** C {@code modBankStr}: the modifierBank1..4 resources (C default "" each). */
    private final String[] modifierBanks = {"", "", "", ""};
    /** C {@code modBankStr[i]} is MAX_CHARS (80) bytes: Get_resource keeps at most 79 chars. */
    public static final int MAX_MODIFIER_BANK = Const.MAX_CHARS - 1;
    /** C {@code maxVolume} from the file, -1 when the file does not set it. */
    private int maxVolume = -1;
    private final List<String> problems = new ArrayList<>();
    private final String source;
    private String shipShape;
    private String shipShapeFile;

    private KeyConfig(String source) {
        this.bindings = KeyBindings.defaults();
        this.source = source;
        for (String[] s : SUPPLEMENT) {
            bindings.add(KeyFunctions.byResource(s[0]), s[1]);
        }
    }

    /** BEHAVIOR: defaults and supplement, Escape unbound, the swap key (when not null) bound. */
    public static KeyConfig defaults(String swapKey) {
        KeyConfig c = new KeyConfig("defaults");
        c.finish(swapKey);
        return c;
    }

    /**
     * Apply a resource file.
     * <p>
     * BEHAVIOR: each {@code keyXxx} entry replaces that function's keys (the last entry wins); an empty
     * value unbinds it; unknown keysyms are reported (with the right spelling when only the case is wrong)
     * and skipped, the other keysyms of the line still apply; Command/Super keysyms are reported and never
     * bound; a keysym JXPilot cannot see (no GLFW key) is reported.
     * BEHAVIOR: power, turnSpeed, turnResistance, altPower, altTurnSpeed and altTurnResistance set the
     * control settings; a value that is not a number is reported; one outside C's range (5-55, 4-64, 0-1)
     * is clamped and reported.
     * BEHAVIOR: shipShape and shipShapeFile are kept as given ({@link #shipShape()}, {@link #shipShapeFile()};
     * the last entry wins).
     * BEHAVIOR: maxVolume (C default.c) sets {@link #maxVolume()}: an integer, clamped to C's 0-255 and
     * reported when outside; a value that is not an integer is reported and ignored.
     * BEHAVIOR: modifierBank1..4 (C default.c) set {@link #modifierBanks()} to the text as written (the server
     * parses it); a text over 79 chars is cut to 79 (C's MAX_CHARS buffer) and reported.
     * BEHAVIOR: an unknown {@code key...} resource is reported; other resources are reported as ignored.
     * BEHAVIOR: binding Escape is reported (it always quits); parse problems of the file are reported.
     *
     * @param sourceName file name used in problem messages
     */
    public static KeyConfig load(String text, String sourceName, String swapKey) {
        KeyConfig c = new KeyConfig("defaults + " + sourceName);
        XpilotRc.Result r = XpilotRc.parse(text);
        for (XpilotRc.Problem p : r.problems()) {
            c.problem(sourceName, p.line(), p.message());
        }
        for (XpilotRc.Entry e : r.entries()) {
            c.apply(sourceName, e);
        }
        c.finish(swapKey);
        return c;
    }

    private void apply(String src, XpilotRc.Entry e) {
        KeyFunctions.Fn f = KeyFunctions.byResource(e.name());
        if (f != null) {
            bindings.set(f, usableKeysyms(src, e));
            return;
        }
        if (setting(src, e)) {
            return;
        }
        if (e.name().equals("shipShape")) {
            shipShape = e.value();
            return;
        }
        if (e.name().equals("shipShapeFile")) {
            shipShapeFile = e.value();
            return;
        }
        if (e.name().startsWith("key")) {
            problem(src, e.line(), "unknown key function '" + e.name() + "'");
        } else {
            problem(src, e.line(), "resource '" + e.name() + "' is not used by JXPilot (ignored)");
        }
    }

    private String usableKeysyms(String src, XpilotRc.Entry e) {
        StringBuilder ok = new StringBuilder();
        String v = e.value().trim();
        for (String s : v.isEmpty() ? new String[0] : v.split("\\s+")) {
            if (KeySyms.reserved(s)) {
                problem(src, e.line(), e.name() + ": " + s + " (Command/Super) cannot be bound, skipped");
                continue;
            }
            if (!KeySyms.known(s)) {
                String hint = KeySyms.suggest(s);
                problem(src, e.line(), e.name() + ": unknown keysym '" + s + "'"
                    + (hint != null ? " (did you mean '" + hint + "'?)" : "") + ", skipped");
                continue;
            }
            if (KeySyms.glfwKey(s) == KeySyms.NONE) {
                problem(src, e.line(), e.name() + ": keysym '" + s + "' has no key JXPilot can read, skipped");
                continue;
            }
            if (KeySyms.glfwKey(s) == GLFW_KEY_ESCAPE) {
                problem(src, e.line(), e.name() + ": Escape always quits JXPilot, skipped");
                continue;
            }
            ok.append(ok.isEmpty() ? "" : " ").append(s);
        }
        return ok.toString();
    }

    private boolean setting(String src, XpilotRc.Entry e) {
        String n = e.name().toLowerCase(Locale.ROOT);
        if (n.equals("maxvolume")) {
            volume(src, e);
            return true;
        }
        if (n.length() == "modifierbank1".length() && n.startsWith("modifierbank")
            && n.charAt(n.length() - 1) >= '1' && n.charAt(n.length() - 1) <= '4') {
            String v = e.value();
            if (v.length() > MAX_MODIFIER_BANK) {
                problem(src, e.line(), e.name() + ": longer than " + MAX_MODIFIER_BANK + " chars, cut");
                v = v.substring(0, MAX_MODIFIER_BANK);
            }
            modifierBanks[n.charAt(n.length() - 1) - '1'] = v;
            return true;
        }
        double lo;
        double hi;
        switch (n) {
            case "power", "altpower" -> {
                lo = Const.MIN_PLAYER_POWER;
                hi = Const.MAX_PLAYER_POWER;
            }
            case "turnspeed", "altturnspeed" -> {
                lo = Const.MIN_PLAYER_TURNSPEED;
                hi = Const.MAX_PLAYER_TURNSPEED;
            }
            case "turnresistance", "altturnresistance" -> {
                lo = Const.MIN_PLAYER_TURNRESISTANCE;
                hi = Const.MAX_PLAYER_TURNRESISTANCE;
            }
            default -> {
                return false;
            }
        }
        double v;
        try {
            v = Double.parseDouble(e.value().trim());
        } catch (NumberFormatException ex) {
            problem(src, e.line(), e.name() + ": not a number: '" + e.value() + "'");
            return true;
        }
        if (!(v >= lo && v <= hi)) {
            double c = Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
            problem(src, e.line(), e.name() + ": " + e.value() + " is outside " + fmt(lo) + "-" + fmt(hi)
                + ", using " + fmt(c));
            v = c;
        }
        switch (n) {
            case "power" -> settings.power = v;
            case "altpower" -> settings.altPower = v;
            case "turnspeed" -> settings.turnSpeed = v;
            case "altturnspeed" -> settings.altTurnSpeed = v;
            case "turnresistance" -> settings.turnResistance = v;
            default -> settings.altTurnResistance = v;
        }
        return true;
    }

    private void volume(String src, XpilotRc.Entry e) {
        int v;
        try {
            v = Integer.parseInt(e.value().trim());
        } catch (NumberFormatException ex) {
            problem(src, e.line(), e.name() + ": not an integer: '" + e.value() + "'");
            return;
        }
        if (v < 0 || v > SoundPlayer.MAX_MAX_VOLUME) {
            int c = Math.max(0, Math.min(SoundPlayer.MAX_MAX_VOLUME, v));
            problem(src, e.line(), e.name() + ": " + v + " is outside 0-" + SoundPlayer.MAX_MAX_VOLUME + ", using " + c);
            v = c;
        }
        maxVolume = v;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private void finish(String swapKey) {
        bindings.unbind(GLFW_KEY_ESCAPE);
        if (swapKey != null) {
            bindings.bind(ClientAction.SWAP_SCALEFACTOR, swapKey);
        }
    }

    private void problem(String src, int line, String msg) {
        problems.add(src + ":" + line + ": " + msg);
    }

    public KeyBindings bindings() { return bindings; }

    public ControlSettings settings() { return settings; }

    /** A copy of the four modifier bank texts (modifierBank1..4; "" when not set). */
    public String[] modifierBanks() { return modifierBanks.clone(); }

    /** The file's C {@code maxVolume} (0..255), or -1 when it does not set one. */
    public int maxVolume() { return maxVolume; }

    /** Every problem found, in file order ({@code source:line: message}). */
    public List<String> problems() { return List.copyOf(problems); }

    /** "defaults" or "defaults + FILE". */
    public String source() { return source; }

    /** The file's {@code shipShape} resource (C default.c), or null when it has none. */
    public String shipShape() { return shipShape; }

    /** The file's {@code shipShapeFile} resource, or null when it has none. */
    public String shipShapeFile() { return shipShapeFile; }

    /**
     * Keys bound to more than one function where C's defaults do not already do so (C allows it, so it is
     * only a warning), as {@code "u: keyDetonateMines keyIdMode"}.
     * <p>
     * BEHAVIOR: the talk-cursor actions do not count (they only act while talking); a key whose functions
     * are exactly C's default set for it (f: refuel + repair, ...) is not a conflict; the key is named by all
     * its spellings ("grave/quoteleft"); sorted.
     */
    public List<String> conflicts() {
        KeyBindings c = KeyBindings.defaults();
        List<String> out = new ArrayList<>();
        for (int g : boundKeys()) {
            List<KeyFunctions.Fn> fns = playFunctions(bindings.functionsOf(g));
            if (fns.size() < 2 || fns.equals(playFunctions(c.functionsOf(g)))) {
                continue;
            }
            StringBuilder sb = new StringBuilder(keyName(g)).append(':');
            for (KeyFunctions.Fn f : fns) {
                sb.append(' ').append(f.resource());
            }
            out.add(sb.toString());
        }
        out.sort(null);
        return out;
    }

    /** The GLFW keys conflicts() reports. */
    private Set<Integer> conflictKeys() {
        KeyBindings c = KeyBindings.defaults();
        Set<Integer> s = new LinkedHashSet<>();
        for (int g : boundKeys()) {
            List<KeyFunctions.Fn> fns = playFunctions(bindings.functionsOf(g));
            if (fns.size() >= 2 && !fns.equals(playFunctions(c.functionsOf(g)))) {
                s.add(g);
            }
        }
        return s;
    }

    /** Every GLFW key some function is bound to, in binding order. */
    private Set<Integer> boundKeys() {
        Set<Integer> keys = new LinkedHashSet<>();
        for (KeyFunctions.Fn f : KeyFunctions.all()) {
            for (String s : bindings.keysyms(f)) {
                int g = KeySyms.glfwKey(s);
                if (g != KeySyms.NONE) {
                    keys.add(g);
                }
            }
        }
        return keys;
    }

    /** BEHAVIOR: every spelling used for the key, sorted and joined with '/' ("grave/quoteleft"). */
    private String keyName(int g) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (KeyFunctions.Fn f : KeyFunctions.all()) {
            for (String s : bindings.keysyms(f)) {
                if (KeySyms.glfwKey(s) == g) {
                    names.add(s);
                }
            }
        }
        return String.join("/", names);
    }

    private static List<KeyFunctions.Fn> playFunctions(List<KeyFunctions.Fn> fns) {
        List<KeyFunctions.Fn> out = new ArrayList<>();
        for (KeyFunctions.Fn f : fns) {
            if (f.action() == null || !f.action().talkCursor()) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * The {@code --list-keys} table: a header naming the source, one line per function
     * ({@code resource  keysyms  help}), conflicting keysyms marked with {@code *}, functions JXPilot does
     * not implement marked "(not in JXPilot)", then the conflicts, the control settings and the problems.
     */
    public List<String> listing() {
        Set<Integer> bad = conflictKeys();
        List<String> out = new ArrayList<>();
        out.add("JXPilot key bindings (" + source + "); * = key bound to more than one function");
        for (KeyFunctions.Fn f : KeyFunctions.all()) {
            String keys = keysText(f, bad);
            String help = f.help() + (f.action() != null && !f.action().implemented() ? " (not in JXPilot)" : "");
            out.add(String.format(Locale.ROOT, "%-24s %-26s %s", f.resource(), keys.isEmpty() ? "-" : keys, help));
        }
        List<String> conf = conflicts();
        out.add("");
        out.add(conf.isEmpty() ? "No conflicting keys." : "Keys bound to more than one function:");
        for (String c : conf) {
            out.add("  " + c);
        }
        out.add("");
        out.add(String.format(Locale.ROOT, "power %s turnSpeed %s turnResistance %s altPower %s altTurnSpeed %s"
                + " altTurnResistance %s", fmt(settings.power), fmt(settings.turnSpeed), fmt(settings.turnResistance),
            fmt(settings.altPower), fmt(settings.altTurnSpeed), fmt(settings.altTurnResistance)));
        if (!problems.isEmpty()) {
            out.add("");
            out.add("Problems:");
            for (String p : problems) {
                out.add("  " + p);
            }
        }
        return out;
    }

    private String keysText(KeyFunctions.Fn f, Set<Integer> bad) {
        StringBuilder sb = new StringBuilder();
        for (String s : bindings.keysyms(f)) {
            int g = KeySyms.glfwKey(s);
            if (g == KeySyms.NONE) {
                continue;
            }
            sb.append(sb.isEmpty() ? "" : " ").append(s).append(bad.contains(g) ? "*" : "");
        }
        return sb.toString();
    }

    /**
     * The help overlay's entries: {@code {keys, help}} for every bound function JXPilot implements (talk
     * cursor keys left out), in C's order, keys marked as in {@link #listing()}; then one entry per
     * conflict ({@code {"! u", "keyDetonateMines keyIdMode"}}).
     */
    public List<String[]> helpLines() {
        Set<Integer> bad = conflictKeys();
        List<String[]> out = new ArrayList<>();
        for (KeyFunctions.Fn f : KeyFunctions.all()) {
            if (f.action() != null && (!f.action().implemented() || f.action().talkCursor())) {
                continue;
            }
            String keys = keysText(f, bad);
            if (!keys.isEmpty()) {
                out.add(new String[] {keys, f.help()});
            }
        }
        for (String c : conflicts()) {
            int colon = c.indexOf(':');
            out.add(new String[] {"* " + c.substring(0, colon), "bound to" + c.substring(colon + 1)});
        }
        return out;
    }
}
