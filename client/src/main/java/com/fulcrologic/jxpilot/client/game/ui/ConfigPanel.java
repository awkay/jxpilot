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
import com.fulcrologic.jxpilot.client.game.hud.HudColor;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * The C client's CONFIG window ({@code configure.c}): pages of 256-pixel-wide forms over the score list, each
 * with CLOSE, NEXT and PREV buttons at the bottom, holding one row per option (a label and a Yes/No button, or
 * a value with left and right arrows) and, last, "Save Configuration". Layout follows {@code Create_config} and
 * {@code Config_create_bool/int/float/save} exactly; pointer behaviour follows {@code widget.c} (a widget acts
 * when the button is released over it). Pure state and layout; drawing and clicks allocate nothing after
 * {@link #layout}.
 */
public final class ConfigPanel {
    /** C {@code config_space}. */
    static final int SPACE = 6;
    /** C {@code config_text_space} / {@code config_button_space}. */
    static final int TEXT_SPACE = 3;
    static final String SAVE_LABEL = "Save Configuration";
    static final String SAVING_FAILED = "Saving failed...";

    /** Widget kinds on a page. */
    enum Type { LABEL, BOOL, INPUT, LEFT, RIGHT, CLOSE, NEXT, PREV, SAVE }

    /** A placed widget (C: one X window). */
    static final class Widget {
        final Type type;
        final int entry;
        final int x;
        final int y;
        final int w;
        final int h;

        Widget(Type type, int entry, int x, int y, int w, int h) {
            this.type = type;
            this.entry = entry;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private final List<ConfigEntry> entries;
    private final List<List<Widget>> pages = new ArrayList<>();
    private final CharBuf scratch = new CharBuf(32);
    private int formY;
    private int formH;
    private int page;
    private boolean open;
    private Widget pressed;
    private boolean inside;
    private String saveLabel = SAVE_LABEL;

    /** @param entries the options in C's {@code config_creator} order (the save button is added last) */
    public ConfigPanel(List<ConfigEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Lay the pages out for a form at y = {@code formY}, 256 x {@code formH} (C {@code config_y = RadarHeight +
     * ButtonHeight + 2}, {@code config_height = top_height - config_y}).
     * <p>
     * BEHAVIOR: text height = 2 + text ascent + descent; button height = 2 + button ascent + descent; entry
     * height = the larger; a Yes/No button is width("Yes") + 6 wide, an int field 4 + width("1000"), a float
     * field 4 + width("0.22") (button font), an arrow text-height square.
     * BEHAVIOR: each page: CLOSE at x 6, NEXT centred, PREV right (6 from the edge), all
     * {@code formH - 6 - buttonHeight} down; rows from y 6 down, each entry height + 6 apart; a row whose label
     * does not fit left of its widget takes two lines (label, then the widget); a row that would reach
     * {@code formH - 2*(entry height) - 2*6} (three entry heights for a two-line row) goes to a new page; the save
     * button is centred in what is left of the last page and needs one entry height + 6 more.
     * BEHAVIOR: a row that does not fit even on an empty page is placed there anyway (C gives up on CONFIG then).
     * The page index is kept when it still exists, else reset to 0.
     */
    public void layout(int formY, int formH, TextMetrics text, TextMetrics button) {
        this.formY = formY;
        this.formH = formH;
        pages.clear();
        pressed = null;
        int textH = 2 + text.ascent() + text.descent();
        int buttonH = 2 + button.ascent() + button.descent();
        int entryH = Math.max(textH, buttonH);
        int boolW = button.width("Yes", 3) + 2 * TEXT_SPACE;
        int intW = 4 + button.width("1000", 4);
        int floatW = 4 + button.width("0.22", 4);
        int arrow = textH;
        List<Widget> current = null;
        int height = 0;
        for (int i = 0; i <= entries.size(); i++) {
            if (current == null) {
                current = newPage(formH, buttonH, button);
                height = SPACE;
            }
            boolean empty = height == SPACE;
            if (i == entries.size()) {
                int w = 2 * TEXT_SPACE + button.width(SAVE_LABEL, SAVE_LABEL.length());
                int space = formH - (height + 2 * entryH + 2 * SPACE);
                if (space < 0 && !empty) {
                    current = null;
                    i--;
                    continue;
                }
                current.add(new Widget(Type.SAVE, -1, (ButtonBar.FORM_WIDTH - w) / 2, height + Math.max(0, space) / 2,
                    w, buttonH));
                break;
            }
            ConfigEntry e = entries.get(i);
            if (height + 2 * entryH + 2 * SPACE >= formH && !empty) {
                current = null;
                i--;
                continue;
            }
            int labelW = text.width(e.label, e.label.length()) + 2 * TEXT_SPACE;
            int widgetW = e.kind == ConfigEntry.Kind.BOOL ? boolW
                : 2 * arrow + (e.kind == ConfigEntry.Kind.INT ? intW : floatW);
            int offset = ButtonBar.FORM_WIDTH - (SPACE + widgetW);
            boolean twoLines = SPACE + labelW > offset;
            if (twoLines && height + 3 * entryH + 2 * SPACE >= formH && !empty) {
                current = null;
                i--;
                continue;
            }
            current.add(new Widget(Type.LABEL, i, SPACE, height + (entryH - textH) / 2, labelW, textH));
            if (twoLines) {
                height += entryH;
            }
            if (e.kind == ConfigEntry.Kind.BOOL) {
                current.add(new Widget(Type.BOOL, i, offset, height + (entryH - buttonH) / 2, boolW, buttonH));
            } else {
                int fieldW = e.kind == ConfigEntry.Kind.INT ? intW : floatW;
                int y = height + (entryH - textH) / 2;
                current.add(new Widget(Type.INPUT, i, offset, y, fieldW, textH));
                current.add(new Widget(Type.LEFT, i, offset + fieldW, height + (entryH - arrow) / 2, arrow, arrow));
                current.add(new Widget(Type.RIGHT, i, offset + fieldW + arrow, height + (entryH - arrow) / 2, arrow,
                    arrow));
            }
            height += entryH + SPACE;
        }
        if (page >= pages.size()) {
            page = 0;
        }
    }

    private List<Widget> newPage(int formH, int buttonH, TextMetrics button) {
        List<Widget> p = new ArrayList<>();
        int y = formH - SPACE - buttonH;
        int prevW = 2 * TEXT_SPACE + button.width("PREV", 4);
        int nextW = 2 * TEXT_SPACE + button.width("NEXT", 4);
        int closeW = 2 * TEXT_SPACE + button.width("CLOSE", 5);
        p.add(new Widget(Type.PREV, -1, ButtonBar.FORM_WIDTH - prevW - SPACE, y, prevW, buttonH));
        p.add(new Widget(Type.NEXT, -1, (ButtonBar.FORM_WIDTH - nextW) / 2, y, nextW, buttonH));
        p.add(new Widget(Type.CLOSE, -1, SPACE, y, closeW, buttonH));
        pages.add(p);
        return p;
    }

    /** Number of pages. */
    public int pageCount() { return pages.size(); }

    /** The page shown. */
    public int page() { return page; }

    public boolean isOpen() { return open; }

    /** BEHAVIOR: opens the panel (C {@code Config(true)}: the page last shown). */
    public void open() { open = true; }

    /** BEHAVIOR: closes it (C {@code Config(false)}), forgetting a held widget. */
    public void close() {
        open = false;
        pressed = null;
    }

    /** The save button's text: "Save Configuration", or "Saving failed..." after a failed save. */
    public String saveLabel() { return saveLabel; }

    /** BEHAVIOR: the result of a save: success restores the button text, failure shows "Saving failed...". */
    public void saved(boolean ok) {
        saveLabel = ok ? SAVE_LABEL : SAVING_FAILED;
    }

    private Widget hit(int x, int y) {
        if (!open || pages.isEmpty()) {
            return null;
        }
        for (Widget w : pages.get(page)) {
            if (w.type != Type.LABEL && w.type != Type.INPUT && UiDraw.inside(x, y - formY, w.x, w.y, w.w, w.h)) {
                return w;
            }
        }
        return null;
    }

    /** BEHAVIOR: whether (x, y) is on the open panel's form. */
    public boolean covers(int x, int y) {
        return open && UiDraw.inside(x, y, 0, formY, ButtonBar.FORM_WIDTH, formH);
    }

    /** BEHAVIOR: a press on an active widget of the open page holds it; returns whether the panel took the press. */
    public boolean press(int x, int y) {
        Widget w = hit(x, y);
        if (w == null) {
            return covers(x, y);
        }
        pressed = w;
        inside = true;
        return true;
    }

    /** BEHAVIOR: while a widget is held, tracks whether the pointer is over it. */
    public void move(int x, int y) {
        if (pressed != null) {
            inside = hit(x, y) == pressed;
        }
    }

    /**
     * BEHAVIOR: release over the held widget acts: a Yes/No flips its entry, an arrow steps it
     * ({@link ConfigEntry#click}), CLOSE closes, NEXT / PREV move a page (wrapping, as C), SAVE returns
     * {@link UiAction#SAVE}; released elsewhere nothing happens. Returns NONE except for SAVE.
     */
    public UiAction release(int x, int y) {
        if (pressed == null) {
            return UiAction.NONE;
        }
        move(x, y);
        Widget w = pressed;
        pressed = null;
        if (!inside) {
            return UiAction.NONE;
        }
        switch (w.type) {
            case BOOL -> entries.get(w.entry).click(true);
            case LEFT -> entries.get(w.entry).click(false);
            case RIGHT -> entries.get(w.entry).click(true);
            case CLOSE -> close();
            case NEXT -> page = (page + 1) % pages.size();
            case PREV -> page = (page - 1 + pages.size()) % pages.size();
            case SAVE -> {
                return UiAction.SAVE;
            }
            default -> { }
        }
        return UiAction.NONE;
    }

    /** BEHAVIOR: whether a widget is held. */
    public boolean holding() { return pressed != null; }

    /**
     * Draw the open page (nothing while closed).
     * <p>
     * BEHAVIOR: the form filled WINDOW (blue); labels as {@link UiDraw#label}; Yes/No and the buttons as
     * {@link UiDraw#button} without border, inverse while held with the pointer over them; values as
     * {@link UiDraw#input} ({@link ConfigEntry#format}); arrows as {@link UiDraw#arrow}, pressed while held over.
     */
    public void draw(HudFrame out, TextMetrics text, TextMetrics button) {
        if (!open || pages.isEmpty()) {
            return;
        }
        out.rect(0, formY, ButtonBar.FORM_WIDTH, formH, true, HudColor.WINDOW);
        for (Widget w : pages.get(page)) {
            int y = formY + w.y;
            boolean down = w == pressed && inside;
            switch (w.type) {
                case LABEL -> UiDraw.label(out, w.x, y, w.w, w.h, entries.get(w.entry).label, text);
                case BOOL -> UiDraw.button(out, w.x, y, w.w, w.h, 0, entries.get(w.entry).format(scratch), down, button);
                case INPUT -> UiDraw.input(out, w.x, y, w.w, w.h, entries.get(w.entry).format(scratch), text);
                case LEFT -> UiDraw.arrow(out, w.x, y, w.w, w.h, false, down);
                case RIGHT -> UiDraw.arrow(out, w.x, y, w.w, w.h, true, down);
                case CLOSE -> UiDraw.button(out, w.x, y, w.w, w.h, 0, "CLOSE", down, button);
                case NEXT -> UiDraw.button(out, w.x, y, w.w, w.h, 0, "NEXT", down, button);
                case PREV -> UiDraw.button(out, w.x, y, w.w, w.h, 0, "PREV", down, button);
                case SAVE -> UiDraw.button(out, w.x, y, w.w, w.h, 0, saveLabel, down, button);
            }
        }
    }

    /** The widgets of page {@code p} (tests). */
    List<Widget> widgets(int p) { return pages.get(p); }
}
