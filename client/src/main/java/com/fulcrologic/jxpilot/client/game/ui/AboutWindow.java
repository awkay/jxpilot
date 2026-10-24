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

import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.game.hud.HudColor;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;

/**
 * The C client's information window ({@code about.c}): 600 x 700, blue with a 2-pixel white border, four pages
 * (BONUS ITEMS, BONUS ITEMS CONTINUED when the items do not fit on one page, GAME OBJECTIVE, ABOUT XPILOT) of
 * word-wrapped shadowed text, and CLOSE / NEXT / PREV buttons at the bottom. C opens it as a separate X window;
 * here it is drawn over the game view, centred (top-aligned when the view is shorter). The item pictures are
 * left to the painter ({@link #itemCount}, {@link #itemX}, {@link #itemY}). Pure; allocation free after
 * construction.
 */
public final class AboutWindow {
    /** C {@code ABOUT_WINDOW_WIDTH}. */
    public static final int WIDTH = 600;
    /** C {@code ABOUT_WINDOW_HEIGHT}. */
    public static final int HEIGHT = 700;
    /** about.c {@code BORDER}. */
    static final int BORDER = 10;
    /** The window's X border width. */
    static final int FRAME = 2;
    /** C {@code ITEM_SIZE}. */
    static final int ITEM_SIZE = 16;
    /** C {@code ITEM_TRIANGLE_SIZE}. */
    static final int ITEM_TRIANGLE_SIZE = 5 * ITEM_SIZE / 7 + 1;
    static final int PAGES = 4;

    /** C's item texts ({@code xinit.c} {@code itemBitmapData}), in item order. */
    static final String[] ITEM_TEXT = {
        "Extra energy/fuel",
        "Extra front cannons",
        "Extra rear cannon",
        "Afterburner; makes your engines more powerful",
        "Cloaking device; makes you almost invisible, both on radar and on screen",
        "Sensor; enables you to see cloaked opponents more easily",
        "Transporter; enables you to steal equipment from other players",
        "Tank; makes refueling quicker, increases maximum fuel capacity and can be jettisoned to confuse enemies",
        "Mine; can be dropped as a bomb or as a stationary mine",
        "Rocket; can be utilized as smart missile, heatseeking missile, nuclear missile or just a plain unguided "
            + "missile (torpedo)",
        "ECM (Electronic Counter Measures); can be used to disturb electronic equipment, for instance can it be "
            + "used to confuse smart missiles and reprogram robots to seek certain players",
        "Laser; limited range laser beam, costs a lot of fuel, having more laser items increases the range of the "
            + "laser, they can be irrepairably damaged by ECMs",
        "Emergency Thrust; gives emergency thrust capabilities for a limited period",
        "Tractor Beam; gives mutual attractive force to currently locked on ship, this means the heavier your ship, "
            + "the less likely you will move when being tractored or using a tractor",
        "Autopilot; when on, the ship will turn and thrust against the direction of travel",
        "EmergencyShield; gives emergency shield capabilities for a limited period",
        "Deflector; pushes hostile objects away from your ship",
        "Hyperjump; enables you to teleport to a random map location",
        "Phasing Device; lets you fly through anything for a limited period",
        "Mirror; reflects laser beams",
        "Armor; absorbs shots in the absence of shields",
    };

    static final String ITEMS_HEAD = "BONUS ITEMS\n\nScattered around the world you might find some of these red "
        + "triangle objects.  They are well worth picking up since they either improve on the equipment you have, "
        + "or they give you new equipment.  If a fighter explodes, some of its equipment might be found among the "
        + "debris.";
    static final String ITEMS_CONTINUED = "BONUS ITEMS CONTINUED\n";
    static final String OBJECTIVE = "GAME OBJECTIVE\n\nXPilot is a multi-player 2D space game.  Some features are "
        + "borrowed from classics like the Atari coin-ups Asteroids and Gravitar, and the home-computer games Thrust "
        + "(Commdore 64) and Gravity Force, but XPilot has many new features as well.\n\nThe primary goal of the "
        + "game is to collect points and increase your rating by destroying enemy fighters and cannons.  You are "
        + "equipped with a machine gun when you start the game, but after a while you should have managed to collect "
        + "some other fancy equipment.\n\nAnother important task is to refuel your ship.  This is vital because your "
        + "engine, radar, weapons and shields all require fuel.  Some even work better the more fuel you have aboard "
        + "(mainly the radar).\n\nOptional modes include variations on this game play: you can play together in "
        + "teams, you can disable shields (and all other equipment if you like), you can race against time and "
        + "fellow players, and much much more.";
    static final String ABOUT = "ABOUT XPILOT\n\nThe game was conceived in its orignal form at the University of "
        + "Tromsø (Norway) by Ken Ronny Schouten and Bjørn Stabell during the fall of 1991, but much of the "
        + "game today is the result of hard efforts by Bert Gijsbers of the molecular cytology lab at the University "
        + "of Amsterdam (The Netherlands).  Bert joined the team in the spring of 1993.\n\nDick Balaska "
        + "(Connecticut, USA) ported XPilot to Windows 95 and NT in the summer of 1996.\n\nA large number of features "
        + "have been contributed by XPilot fans from all around the world.  See the CREDITS file for details.\n\n"
        + "For more information, read the XPilot FAQ (Frequently Asked Questions), and the on-line manual pages for "
        + "xpilot(6) and xpilots(6).\n\nYou can report any bug you find to <xpilot@xpilot.org>.\n\n\nGood luck as a "
        + "future xpilot,\nBjørn Stabell, Ken Ronny Schouten, Bert Gijsbers & Dick Balaska\n\nJXPilot is an "
        + "independent Java port of XPilot 4.5.5 (github.com/awkay/jxpilot); the XPilot authors were not involved in "
        + "it.  Report JXPilot bugs there, not to the address above.";

    private static final String[] BUTTONS = {"CLOSE", "NEXT", "PREV"};

    private final CharBuf word = new CharBuf(128);
    private final int[] itemX = new int[ITEM_TEXT.length];
    private final int[] itemY = new int[ITEM_TEXT.length];
    private final int[] itemNumber = new int[ITEM_TEXT.length];
    private int items;
    private boolean open;
    private int page;
    /** C {@code itemsplit}: the last item on page 0 (-1: not computed yet). */
    private int itemSplit = -1;
    private int x0;
    private int y0;
    private int buttonH;
    private final int[] buttonX = new int[3];
    private final int[] buttonW = new int[3];
    private int pressed = -1;

    public boolean isOpen() { return open; }

    public int page() { return page; }

    /** BEHAVIOR: opens the window on the page it was left at (C maps it again). */
    public void open() { open = true; }

    /** C {@code itemsplit} (-1 until page 0 was laid out). */
    public int itemSplit() { return itemSplit; }

    /** Item pictures of the last {@link #draw}: count, item number and centre (C {@code Paint_item} position). */
    public int itemCount() { return items; }

    public int item(int i) { return itemNumber[i]; }

    public int itemX(int i) { return itemX[i]; }

    public int itemY(int i) { return itemY[i]; }

    /**
     * Place the window in a view of {@code viewW x viewH} and size its buttons.
     * <p>
     * BEHAVIOR: centred, but never above or left of the view's top-left corner; button height
     * 2 * BTN_BORDER + button ascent + descent; CLOSE at x BORDER, NEXT at WIDTH/2 - BTN_BORDER - width/2, PREV at
     * WIDTH - BORDER - 2 * BTN_BORDER - width, all HEIGHT - BORDER - height - 4 down (inside coordinates); a
     * button is 2 * BTN_BORDER + its text width wide.
     */
    public void layout(int viewW, int viewH, TextMetrics button) {
        x0 = Math.max(0, (viewW - (WIDTH + 2 * FRAME)) / 2);
        y0 = Math.max(0, (viewH - (HEIGHT + 2 * FRAME)) / 2);
        buttonH = 2 * ButtonBar.BTN_BORDER + button.ascent() + button.descent();
        for (int i = 0; i < 3; i++) {
            int tw = button.width(BUTTONS[i], BUTTONS[i].length());
            buttonW[i] = 2 * ButtonBar.BTN_BORDER + tw;
            buttonX[i] = i == 0 ? BORDER : i == 1 ? WIDTH / 2 - ButtonBar.BTN_BORDER - tw / 2
                : WIDTH - BORDER - 2 * ButtonBar.BTN_BORDER - tw;
        }
    }

    private int insideX() { return x0 + FRAME; }

    private int insideY() { return y0 + FRAME; }

    private int buttonY() { return HEIGHT - BORDER - buttonH - 4; }

    private int hitButton(int x, int y) {
        for (int i = 0; i < 3; i++) {
            if (UiDraw.inside(x, y, insideX() + buttonX[i], insideY() + buttonY(), buttonW[i], buttonH)) {
                return i;
            }
        }
        return -1;
    }

    /** BEHAVIOR: whether (x, y) is on the open window (its border included). */
    public boolean covers(int x, int y) {
        return open && UiDraw.inside(x, y, x0, y0, WIDTH + 2 * FRAME, HEIGHT + 2 * FRAME);
    }

    /** BEHAVIOR: a press on a button holds it; a press anywhere on the open window is taken. */
    public boolean press(int x, int y) {
        if (!covers(x, y)) {
            return false;
        }
        pressed = hitButton(x, y);
        return true;
    }

    /**
     * BEHAVIOR: release after pressing a button (C acts on the release of the pressed button window wherever the
     * pointer is): CLOSE closes and resets to page 0; NEXT / PREV step the page, wrapping, skipping page 1 when all
     * items fit on page 0 (C {@code About}).
     */
    public void release() {
        int b = pressed;
        pressed = -1;
        if (b == 0) {
            page = 0;
            open = false;
        } else if (b == 1) {
            page++;
            if (page == 1 && itemSplit >= ITEM_TEXT.length - 1) {
                page++;
            }
            if (page >= PAGES) {
                page = 0;
            }
        } else if (b == 2) {
            page--;
            if (page == 1 && itemSplit >= ITEM_TEXT.length - 1) {
                page--;
            }
            if (page <= -1) {
                page = PAGES - 1;
            }
        }
    }

    /** BEHAVIOR: whether a button is held. */
    public boolean holding() { return pressed >= 0; }

    /**
     * Draw the open window ({@code Expose_about_window}).
     * <p>
     * BEHAVIOR: a white 2-pixel frame and the blue inside; the page's text word-wrapped at WIDTH - BORDER from x
     * BORDER ({@link #wrap}); on the item pages, per item a black box at x BORDER, 2 * ITEM_SIZE + 2 * BORDER
     * wide, behind the item picture at (2 * BORDER + ITEM_SIZE, text top + ITEM_TRIANGLE_SIZE) and the item's text
     * at x 5 * BORDER + 2 * ITEM_SIZE; page 0 stops before the item whose box would reach the buttons and records
     * the split (C {@code itemsplit}); the buttons RED (BLACK while held) with shadowed labels at (BTN_BORDER,
     * ascent + BTN_BORDER).
     */
    public void draw(HudFrame out, TextMetrics text, TextMetrics button) {
        items = 0;
        if (!open) {
            return;
        }
        int ix = insideX();
        int iy = insideY();
        out.rect(x0, y0, WIDTH + 2 * FRAME, HEIGHT + 2 * FRAME, true, HudColor.WHITE);
        out.rect(ix, iy, WIDTH, HEIGHT, true, HudColor.WINDOW);
        if (page <= 1) {
            itemsPage(out, text, ix, iy);
        } else {
            wrap(out, text, ix, iy, BORDER, BORDER, page == 2 ? OBJECTIVE : ABOUT);
        }
        for (int i = 0; i < 3; i++) {
            int bx = ix + buttonX[i];
            int by = iy + buttonY();
            out.rect(bx, by, buttonW[i], buttonH, true, pressed == i ? HudColor.BLACK : HudColor.BUTTON);
            UiDraw.text(out, bx + ButtonBar.BTN_BORDER, by + button.ascent() + ButtonBar.BTN_BORDER, HudColor.SCORE,
                FontRole.BUTTON, BUTTONS[i]);
        }
    }

    private void itemsPage(HudFrame out, TextMetrics text, int ix, int iy) {
        int y;
        int first;
        int last;
        if (page == 0) {
            y = wrap(out, text, ix, iy, BORDER, BORDER, ITEMS_HEAD);
            first = 0;
            last = itemSplit == -1 ? ITEM_TEXT.length - 1 : itemSplit;
        } else {
            y = wrap(out, text, ix, iy, BORDER, BORDER, ITEMS_CONTINUED);
            first = itemSplit + 1;
            last = ITEM_TEXT.length - 1;
        }
        y += BORDER;
        int boxStart = y;
        y += BORDER / 2;
        int limit = HEIGHT - BORDER * 2 - 4 - buttonH;
        for (int i = first; i <= last; i++) {
            y += BORDER / 2;
            int oldY = y;
            int rects = out.rectCount();
            int texts = out.textCount();
            y = wrap(out, text, ix, iy, 5 * BORDER + 2 * ITEM_SIZE, oldY, ITEM_TEXT[i]);
            if (y - oldY < 2 * ITEM_TRIANGLE_SIZE) {
                y = oldY + 2 * ITEM_TRIANGLE_SIZE;
            }
            int boxEnd = y + BORDER / 2;
            if (i == last) {
                boxEnd += BORDER / 2;
            }
            if (page == 0 && itemSplit == -1 && boxEnd >= limit) {
                itemSplit = i - 1;
                out.truncate(rects, texts);
                break;
            }
            out.rect(ix + BORDER, iy + boxStart, 2 * ITEM_SIZE + 2 * BORDER, boxEnd - boxStart, true, HudColor.BLACK);
            itemNumber[items] = i;
            itemX[items] = ix + 2 * BORDER + ITEM_SIZE;
            itemY[items] = iy + oldY + ITEM_TRIANGLE_SIZE;
            items++;
            y = boxEnd;
            boxStart = boxEnd;
        }
        if (page == 0 && itemSplit == -1) {
            itemSplit = ITEM_TEXT.length - 1;
        }
    }

    /**
     * C {@code DrawShadowText}: word-wrapped shadowed white text in a window WIDTH wide.
     * <p>
     * BEHAVIOR: the first baseline is yStart + ascent; a word that would end past WIDTH - BORDER starts a new line
     * at x xBorder, ascent + descent + 1 lower; a newline starts a new line; each blank advances by the width of a
     * space; one text run per line (text from the line's first word to its last, spacing as written). Returns the
     * last baseline + descent + 1; empty text returns 0 and draws nothing.
     */
    int wrap(HudFrame out, TextMetrics m, int ix, int iy, int xBorder, int yStart, String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int lineH = m.ascent() + m.descent() + 1;
        int x = xBorder;
        int y = yStart + m.ascent();
        int space = m.width(" ", 1);
        int lineStart = -1;
        int lineEnd = -1;
        int lineX = xBorder;
        int lineY = y;
        int i = 0;
        int n = s.length();
        do {
            int ws = i;
            word.clear();
            while (i < n && !isSpace(s.charAt(i)) && word.length() < 127) {
                word.append(s.charAt(i));
                i++;
            }
            int wordLen = m.width(word, word.length());
            if (x + wordLen > WIDTH - BORDER) {
                emit(out, ix, iy, lineX, lineY, s, lineStart, lineEnd);
                x = xBorder;
                y += lineH;
                lineStart = -1;
            }
            if (lineStart < 0) {
                lineStart = ws;
                lineX = x;
                lineY = y;
            }
            x += wordLen;
            lineEnd = i;
            while (i < n && isSpace(s.charAt(i))) {
                if (s.charAt(i) == '\n') {
                    emit(out, ix, iy, lineX, lineY, s, lineStart, lineEnd);
                    lineStart = -1;
                    x = xBorder;
                    y += lineH;
                } else {
                    x += space;
                }
                i++;
            }
        } while (i < n);
        emit(out, ix, iy, lineX, lineY, s, lineStart, lineEnd);
        return y + m.descent() + 1;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\n' || c == '\t';
    }

    private static void emit(HudFrame out, int ix, int iy, int x, int y, String s, int from, int to) {
        if (from < 0 || to <= from) {
            return;
        }
        CharBuf t = out.text(ix + x, iy + y, HudColor.SCORE);
        if (t != null) {
            for (int k = from; k < to; k++) {
                t.append(s.charAt(k));
            }
        }
    }
}
