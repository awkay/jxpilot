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

import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.input.ControlSettings;
import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.game.options.RcWriter;
import com.fulcrologic.jxpilot.client.game.ui.ConfigEntry;
import com.fulcrologic.jxpilot.client.game.ui.UiAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

@DisplayName("The C client's windows (button bar, CONFIG, ABOUT)")
class ClientUiTest {
    record Mono() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 3; }

        public int width(CharSequence s, int len) { return 7 * len; }
    }

    static final Mono M = new Mono();

    static final class Host implements ClientUi.Host {
        final List<String> calls = new ArrayList<>();
        boolean saveOk = true;
        List<RcWriter.Resource> saved;
        int fps = 50;

        public void quit() { calls.add("quit"); }

        public void toggleKeys() { calls.add("keys"); }

        public void showMotd(boolean refresh) { calls.add("motd " + refresh); }

        public void showRealName(boolean on) { calls.add("realName " + on); }

        public void settingsChanged(boolean meters) { calls.add("settings " + meters); }

        public int serverFps() { return fps; }

        public boolean save(List<RcWriter.Resource> r) {
            saved = r;
            calls.add("save");
            return saveOk;
        }
    }

    final Host host = new Host();
    final ClientOptions options = new ClientOptions();
    final ControlSettings settings = new ControlSettings();
    final ClientUi ui = new ClientUi(host, options, settings, 100);

    static List<String> labels(List<ConfigEntry> e) {
        List<String> l = new ArrayList<>();
        for (ConfigEntry c : e) {
            l.add(c.label);
        }
        return l;
    }

    @Nested
    @DisplayName("CONFIG entries")
    class Entries {
        @Test
        @DisplayName("C's config_creator order; slidingRadar only on edge-wrap maps")
        void order() {
            List<String> wrap = labels(ui.entries(true));
            List<String> flat = labels(ui.entries(false));
            assertAll(
                () -> assertEquals(List.of("power", "turnSpeed", "turnResistance", "altPower", "altTurnSpeed",
                    "altTurnResistance", "showMessages", "maxMessages", "messagesToStdout", "reverseScroll",
                    "oldMessagesColor", "showHUD", "showHUDRadar", "horizontalHUDLine", "verticalHUDLine",
                    "speedFactHUD", "speedFactPTR", "fuelNotify", "fuelWarning", "fuelCritical", "fuelGauge",
                    "outlineWorld", "filledWorld", "texturedWalls", "texturedObjects", "slidingRadar", "showItems",
                    "showItemsTime", "showScoreDecimals", "backgroundPointDist", "backgroundPointSize", "sparkSize",
                    "sparkProb", "charsPerSecond", "markingLights", "toggleShield", "autoShield", "nastyShots",
                    "shotSize", "teamShotSize", "hudColor", "hudLockColor", "wallColor", "decorColor", "showDecor",
                    "outlineDecor", "filledDecor", "texturedDecor", "texturedBalls", "maxFPS", "maxVolume",
                    "showShipName", "showMineName", "fuelMeter", "powerMeter", "turnSpeedMeter", "packetSizeMeter",
                    "packetLossMeter", "packetDropMeter", "packetLagMeter", "clock", "clockAMPM", "scaleFactor",
                    "altScaleFactor"), wrap, "every entry in C's order"),
                () -> assertFalse(flat.contains("slidingRadar"), "no slidingRadar without edge wrap"),
                () -> assertEquals(wrap.size() - 1, flat.size(), "only that one is left out"));
        }

        @Test
        @DisplayName("entries edit their options, control settings (sent, meters for power/turn speed), volume and maxFPS")
        void binding() {
            List<ConfigEntry> e = ui.entries(false);
            ConfigEntry showHud = e.get(11);
            ConfigEntry power = e.get(0);
            ConfigEntry resist = e.get(2);
            ConfigEntry hudColor = e.get(39);
            ConfigEntry maxFps = e.get(48);
            ConfigEntry volume = e.get(49);
            ConfigEntry sparkProb = e.get(31);
            showHud.click(true);
            power.click(true);
            resist.click(true);
            options.setInteger(ClientOption.MAX_COLORS, 8);
            hudColor.value.set(12);
            int fpsShown = (int) maxFps.value.get();
            maxFps.value.set(10);
            volume.click(false);
            sparkProb.click(true);
            assertAll(
                () -> assertEquals("hudColor", hudColor.label, "index check"),
                () -> assertFalse(options.flag(ClientOption.SHOW_HUD), "showHUD toggled off"),
                () -> assertEquals(47.0, settings.power, 1e-4, "power stepped"),
                () -> assertEquals(List.of("settings true", "settings false"), host.calls,
                    "power sends with meters, turnResistance without"),
                () -> assertEquals(7, options.integer(ClientOption.HUD_COLOR), "colour limited to maxColors - 1"),
                () -> assertEquals(50, fpsShown, "maxFPS 0 shows the server FPS"),
                () -> assertEquals(25, options.integer(ClientOption.MAX_FPS), "maxFPS limited to FPS/2"),
                () -> assertEquals(95, ui.maxVolume(), "volume stepped down 5%"),
                () -> assertEquals(0.525, options.real(ClientOption.SPARK_PROB), 1e-6, "sparkProb + 5% (float)"));
        }

        @Test
        @DisplayName("maxFps: 0 means the server FPS, other values are limited to FPS/2..FPS")
        void maxFps() {
            int zero = ui.maxFps();
            options.setInteger(ClientOption.MAX_FPS, 200);
            int high = ui.maxFps();
            options.setInteger(ClientOption.MAX_FPS, 30);
            int mid = ui.maxFps();
            options.setInteger(ClientOption.MAX_FPS, 3);
            int low = ui.maxFps();
            assertAll(
                () -> assertEquals(50, zero, "full rate"),
                () -> assertEquals(50, high, "at most FPS"),
                () -> assertEquals(30, mid, "in range"),
                () -> assertEquals(25, low, "at least FPS/2"));
        }
    }

    @Nested
    @DisplayName("actions and mouse")
    class Actions {
        @Test
        @DisplayName("each action reaches the host or opens its window; SCORE/PLAYER close CONFIG")
        void act() {
            ui.act(UiAction.QUIT);
            ui.act(UiAction.KEYS);
            ui.act(UiAction.ABOUT);
            ui.act(UiAction.CONFIG);
            boolean configOpen = ui.config().isOpen();
            ui.act(UiAction.SCORE);
            boolean afterScore = ui.config().isOpen();
            ui.act(UiAction.CONFIG);
            ui.act(UiAction.PLAYER);
            ui.act(UiAction.MOTD);
            options.setFlag(ClientOption.REFRESH_MOTD, true);
            ui.act(UiAction.MOTD);
            ui.act(UiAction.NONE);
            assertAll(
                () -> assertEquals(List.of("quit", "keys", "realName false", "realName true", "motd false", "motd true"),
                    host.calls, "host calls in order; MOTD refreshes iff refreshMotd"),
                () -> assertTrue(ui.about().isOpen(), "ABOUT opened"),
                () -> assertTrue(configOpen, "CONFIG opened"),
                () -> assertFalse(afterScore, "SCORE closed CONFIG"),
                () -> assertFalse(ui.config().isOpen(), "PLAYER closed CONFIG"));
        }

        @Test
        @DisplayName("save: the resources go to the host; a failure shows on the button")
        void save() {
            host.saveOk = false;
            ui.act(UiAction.SAVE);
            String failed = ui.config().saveLabel();
            host.saveOk = true;
            ui.act(UiAction.SAVE);
            assertAll(
                () -> assertEquals("Saving failed...", failed, "failure shown"),
                () -> assertEquals("Save Configuration", ui.config().saveLabel(), "success restores the label"),
                () -> assertEquals("name", host.saved.get(0).name(), "the save list starts with the name"));
        }

        @Test
        @DisplayName("mouse: the bar, CONFIG and ABOUT take left-button presses and get their releases; the rest is the view's")
        void mouse() {
            ui.layout(200, 800, 1000, 800, false, M, M);
            boolean right = ui.mouseButton(GLFW_MOUSE_BUTTON_RIGHT, GLFW_PRESS, 10, 205, 258);
            boolean view = ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 600, 400, 258);
            boolean viewRelease = ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 600, 400, 258);
            boolean viewMove = ui.mouseMove(600, 400, 258);
            ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 100, 205, 258);
            boolean moved = ui.mouseMove(100, 206, 258);
            boolean repeat = ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_REPEAT, 100, 206, 258);
            ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 100, 206, 258);
            boolean aboutOpen = ui.about().isOpen();
            boolean aboutTook = ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 258 + 200, 100, 258);
            boolean aboutMove = ui.mouseMove(258 + 200, 101, 258);
            ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 258 + 200, 100, 258);
            ui.act(UiAction.CONFIG);
            boolean configTook = ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS, 10, 600, 258);
            boolean configMove = ui.mouseMove(10, 601, 258);
            boolean configRelease = ui.mouseButton(GLFW_MOUSE_BUTTON_LEFT, GLFW_RELEASE, 10, 601, 258);
            assertAll(
                () -> assertFalse(right, "other buttons are not the windows'"),
                () -> assertFalse(view, "a press in the view is not taken"),
                () -> assertFalse(viewRelease, "nor its release"),
                () -> assertFalse(viewMove, "nor motion without a held window"),
                () -> assertTrue(moved, "motion while the bar holds the pointer"),
                () -> assertTrue(repeat, "other actions while held are swallowed"),
                () -> assertTrue(aboutOpen, "ABOUT clicked"),
                () -> assertTrue(aboutTook, "the information window takes presses over it"),
                () -> assertTrue(aboutMove, "and motion while held"),
                () -> assertTrue(configTook, "the CONFIG form takes presses"),
                () -> assertTrue(configMove, "and motion"),
                () -> assertTrue(configRelease, "and the release"),
                () -> assertEquals(200 + 23, ui.scoreTop(), "the score list under the bar"),
                () -> assertTrue(ui.coversScores(), "CONFIG covers the score list"));
        }

        @Test
        @DisplayName("layout rebuilds CONFIG for edge wrap (keeping it open) and draws into the three lists")
        void layoutAndDraw() {
            ui.act(UiAction.CONFIG);
            ui.layout(200, 800, 1000, 800, true, M, M);
            boolean stillOpen = ui.config().isOpen();
            ui.act(UiAction.ABOUT);
            HudFrame panel = new HudFrame(64, 1536, 320, 256);
            ui.draw(panel, M, M, 258);
            assertAll(
                () -> assertTrue(stillOpen, "rebuilt CONFIG stays open"),
                () -> assertTrue(panel.rectCount() > 10, "bar and CONFIG drawn in the panel"),
                () -> assertTrue(ui.aboutHud().textCount() > 3, "the information window drawn"),
                () -> assertEquals(0, ui.panelOverlay().rectCount(), "no pulldown while MENU is up"));
        }
    }

    @Nested
    @DisplayName("save list (C Config_save)")
    class SaveList {
        @Test
        @DisplayName("name, settings, C's options in order, volume, scale factors, bound keys, modifier banks; no maxFPS")
        void list() {
            KeyConfig k = KeyConfig.load("keyThrust: w\nmodifierBank1: FN\n", "rc", null);
            List<RcWriter.Resource> r = ui.saveList("Ace", k.bindings(), k.modifierBanks());
            List<String> names = new ArrayList<>();
            for (RcWriter.Resource x : r) {
                names.add(x.name());
            }
            assertAll(
                () -> assertEquals(List.of("name", "power", "turnSpeed", "turnResistance", "altPower", "altTurnSpeed",
                    "altTurnResistance", "speedFactHUD", "speedFactPTR", "fuelNotify", "fuelWarning", "fuelCritical",
                    "showShipName"), names.subList(0, 13), "C's opening order"),
                () -> assertEquals(new RcWriter.Resource("power", "45.000"), r.get(1), "floats %.3f"),
                () -> assertEquals(new RcWriter.Resource("fuelNotify", "500.000"), r.get(9),
                    "fuel levels as floats (C Config_save_float)"),
                () -> assertTrue(names.contains("clientPortEnd"), "the C list through clientPortEnd"),
                () -> assertTrue(names.indexOf("maxVolume") > names.indexOf("clientPortEnd"), "then maxVolume"),
                () -> assertFalse(names.contains("maxFPS"), "maxFPS is never saved"),
                () -> assertTrue(r.contains(new RcWriter.Resource("keyThrust", "w")), "bound keys"),
                () -> assertFalse(names.contains("keyQuit"), "unbound functions are not written"),
                () -> assertEquals(new RcWriter.Resource("modifierBank1", "FN"), r.get(r.size() - 4), "banks last"),
                () -> assertEquals(new RcWriter.Resource("modifierBank4", ""), r.get(r.size() - 1), "empty banks too"),
                () -> assertEquals(4, ui.saveList("A", null, new String[0]).stream()
                    .filter(x -> x.name().startsWith("modifierBank")).count(), "four banks even without texts"));
        }
    }
}
