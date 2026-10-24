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
package com.fulcrologic.jxpilot.client.game.ui;

import com.fulcrologic.jxpilot.client.game.hud.HudColor;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;

/**
 * The C client's button bar under the radar ({@code xinit.c} {@code Init_playing_windows}): a form 256 wide at
 * y = RadarHeight holding QUIT, ABOUT and the MENU pulldown (KEYS, CONFIG, SCORE, PLAYER, MOTD), each button
 * {@link #BUTTON_WIDTH} wide with a 1-pixel white border, with C's pointer behaviour ({@code widget.c}
 * {@code Widget_button}): a button acts when the mouse button is released over it; MENU opens its pulldown
 * while the button is held and the entry under the pointer at release is chosen. Pure state and layout;
 * allocation free after {@link #layout}.
 */
public final class ButtonBar {
    /** C {@code BUTTON_WIDTH}. */
    public static final int BUTTON_WIDTH = 84;
    /** C {@code BTN_BORDER}. */
    public static final int BTN_BORDER = 4;
    /** Width of the form (the radar's). */
    public static final int FORM_WIDTH = 256;
    static final String[] BUTTONS = {"QUIT", "ABOUT", "MENU"};
    static final String[] ENTRIES = {"KEYS", "CONFIG", "SCORE", "PLAYER", "MOTD"};
    static final UiAction[] ENTRY_ACTIONS = {UiAction.KEYS, UiAction.CONFIG, UiAction.SCORE, UiAction.PLAYER,
        UiAction.MOTD};
    private static final int MENU = 2;

    private int top;
    private int buttonHeight;
    private int pullX;
    private int pullY;
    private int pullW;
    private final int[] entryW = new int[ENTRIES.length];
    private int pressed = -1;
    private boolean inside;
    private int hover = -1;

    /**
     * Place the bar under a radar of {@code radarHeight}.
     * <p>
     * BEHAVIOR: C {@code ButtonHeight} = ascent + descent + 2 * BTN_BORDER of the button font; button i at
     * x = i + i * BUTTON_WIDTH. The pulldown hangs under MENU: x = MENU's inside x - 1, y = its inside y + its
     * height; entry i at pulldown y + i * (ButtonHeight + 1), each max(text width + ButtonHeight - (ascent +
     * descent), pulldown width - 2) wide, the pulldown widening to fit (C {@code Widget_add_pulldown_entry}).
     */
    public void layout(int radarHeight, TextMetrics button) {
        top = radarHeight;
        buttonHeight = button.ascent() + button.descent() + 2 * BTN_BORDER;
        pullX = buttonX(MENU) + 1 - 1;
        pullY = top + 1 + buttonHeight;
        pullW = BUTTON_WIDTH + 2;
        for (int i = 0; i < ENTRIES.length; i++) {
            int w = button.width(ENTRIES[i], ENTRIES[i].length()) + buttonHeight - (button.ascent() + button.descent());
            if (w < pullW - 2) {
                w = pullW - 2;
            }
            if (pullW < w + 2) {
                pullW = w + 2;
            }
            entryW[i] = w;
        }
    }

    /** C {@code ButtonHeight}: a button's inside height. */
    public int buttonHeight() { return buttonHeight; }

    /** Height of the bar's form: ButtonHeight + 2 (C: the score list starts at RadarHeight + ButtonHeight + 2). */
    public int height() { return buttonHeight + 2; }

    /** The form's top row (RadarHeight). */
    public int top() { return top; }

    /** True while MENU is held (the pulldown shows). */
    public boolean menuOpen() { return pressed == MENU; }

    static int buttonX(int i) {
        return i + i * BUTTON_WIDTH;
    }

    private int hitButton(int x, int y) {
        for (int i = 0; i < BUTTONS.length; i++) {
            if (UiDraw.inside(x, y, buttonX(i), top, BUTTON_WIDTH + 2, buttonHeight + 2)) {
                return i;
            }
        }
        return -1;
    }

    private int hitEntry(int x, int y) {
        for (int i = 0; i < ENTRIES.length; i++) {
            if (UiDraw.inside(x, y, pullX, entryY(i), entryW[i] + 2, buttonHeight + 2)) {
                return i;
            }
        }
        return -1;
    }

    private int entryY(int i) {
        return pullY + i * (buttonHeight + 1);
    }

    /** BEHAVIOR: a press on a button holds it (MENU opens the pulldown); returns whether the bar took the press. */
    public boolean press(int x, int y) {
        int b = hitButton(x, y);
        if (b < 0) {
            return false;
        }
        pressed = b;
        inside = true;
        hover = -1;
        return true;
    }

    /** BEHAVIOR: pointer motion: while a button is held, tracks whether the pointer is over it (or which entry). */
    public void move(int x, int y) {
        if (pressed < 0) {
            return;
        }
        if (pressed == MENU) {
            hover = hitEntry(x, y);
        } else {
            inside = hitButton(x, y) == pressed;
        }
    }

    /**
     * BEHAVIOR: release: a held QUIT/ABOUT acts iff the pointer is still over it; a held MENU closes its pulldown
     * and chooses the entry under the pointer (none: NONE). Without a held button: NONE.
     */
    public UiAction release(int x, int y) {
        if (pressed < 0) {
            return UiAction.NONE;
        }
        move(x, y);
        int b = pressed;
        pressed = -1;
        if (b == MENU) {
            int e = hover;
            hover = -1;
            return e >= 0 ? ENTRY_ACTIONS[e] : UiAction.NONE;
        }
        return inside ? (b == 0 ? UiAction.QUIT : UiAction.ABOUT) : UiAction.NONE;
    }

    /** BEHAVIOR: whether the bar holds the pointer (a button is pressed). */
    public boolean holding() { return pressed >= 0; }

    /**
     * BEHAVIOR: the form (WINDOW blue, FORM_WIDTH x height) and the three buttons into {@code out}; QUIT and ABOUT
     * inverse while held with the pointer over them, MENU never (C draws the menu button plain); while MENU is
     * held the pulldown (WINDOW blue, then each entry, inverse under the pointer) into {@code overlay}.
     */
    public void draw(HudFrame out, HudFrame overlay, TextMetrics button) {
        out.rect(0, top, FORM_WIDTH, height(), true, HudColor.WINDOW);
        for (int i = 0; i < BUTTONS.length; i++) {
            UiDraw.button(out, buttonX(i), top, BUTTON_WIDTH, buttonHeight, 1, BUTTONS[i],
                i != MENU && pressed == i && inside, button);
        }
        if (pressed == MENU) {
            overlay.rect(pullX, pullY, pullW, ENTRIES.length * (buttonHeight + 1) + 1, true, HudColor.WINDOW);
            for (int i = 0; i < ENTRIES.length; i++) {
                UiDraw.button(overlay, pullX, entryY(i), entryW[i], buttonHeight, 1, ENTRIES[i], hover == i, button);
            }
        }
    }
}
