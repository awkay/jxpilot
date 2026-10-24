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
package com.fulcrologic.jxpilot.client.game.hud;

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.lobby.LobbyKeys;

import java.util.List;

/**
 * The lobby overlay: the maps of a lobby server as a scrollable list over a black panel, shown instead of the game
 * while the player has not chosen one. Each row has its one-key shortcut ({@link LobbyKeys}: 0-9, a-z, A-Z by row,
 * none from the 63rd), an active indicator ({@code *}), the map's name, the number of humans playing it and its
 * title. Up/Down move the cursor and Enter chooses; a shortcut key chooses its row at once; longer lists scroll.
 * <p>
 * The list arrives sorted (active first, then by name) and is replaced as a whole when the server's list changes;
 * the cursor stays on the same map when that happens, so a map that becomes active does not make the cursor jump
 * to another one. The texts of the rows are made when the list changes, so {@link #layout} allocates nothing.
 */
public final class LobbyPanel {
    /** Panel margin and padding (px). */
    public static final int MARGIN = 10;
    /** Gap between columns (px). */
    public static final int GAP = 12;

    private static final String HEADER_HUMANS = "Humans";
    private static final String HEADER_TITLE = "Map";

    private List<LobbyEntry> rows = List.of();
    private String[] keyText = new String[0];
    private String[] humansText = new String[0];
    private int cursor;
    private int top;
    private int pageRows = 10;
    private String heading = "Lobby";
    private String headingLine = "Lobby";
    private String message = "";

    /**
     * Take the lobby's current state.
     * <p>
     * BEHAVIOR: a list object other than the one held replaces it: the cursor stays on the map it was on (by id),
     * else on the same row number clamped to the list, and the first row for the first list.
     * BEHAVIOR: the same list object changes nothing but the heading and message.
     *
     * @param serverName the lobby's name, "" for none
     * @param sorted     the maps in display order
     * @param message    one line shown at the bottom (a start in progress or a refusal), "" for none
     */
    public void update(String serverName, List<LobbyEntry> sorted, String message) {
        String h = serverName.isEmpty() ? "Lobby" : serverName;
        if (!h.equals(heading) || (sorted.isEmpty() != rows.isEmpty())) {
            heading = h;
            headingLine = sorted.isEmpty() ? h : h + ": choose a map";
        }
        this.message = message;
        if (sorted == rows) {
            return;
        }
        int keepId = cursor < rows.size() ? rows.get(cursor).id() : -1;
        rows = sorted;
        int n = rows.size();
        keyText = new String[n];
        humansText = new String[n];
        int found = -1;
        for (int i = 0; i < n; i++) {
            LobbyEntry e = rows.get(i);
            char k = LobbyKeys.keyFor(i);
            keyText[i] = k == 0 ? "" : "[" + k + "]";
            humansText[i] = e.active() ? Integer.toString(e.humans()) : "";
            if (e.id() == keepId) {
                found = i;
            }
        }
        cursor = n == 0 ? 0 : found >= 0 ? found : Math.min(cursor, n - 1);
        top = Math.min(top, Math.max(0, n - 1));
    }

    /** Rows held. */
    public int rows() { return rows.size(); }

    /** The row the cursor is on (0 for an empty list). */
    public int cursor() { return cursor; }

    /** The first row shown. */
    public int top() { return top; }

    /** The rows that fit the panel as of the last {@link #layout} (the page size of PageUp/PageDown). */
    public int pageRows() { return pageRows; }

    /** The map under the cursor, or null for an empty list. */
    public LobbyEntry current() {
        return rows.isEmpty() ? null : rows.get(cursor);
    }

    /**
     * BEHAVIOR: moves the cursor by {@code delta} rows, stopping at the first and last row (no wrap-around), and
     * scrolls so that it stays on screen.
     */
    public void move(int delta) {
        if (rows.isEmpty()) {
            return;
        }
        cursor = Math.max(0, Math.min(rows.size() - 1, cursor + delta));
        scrollToCursor();
    }

    /** BEHAVIOR: moves the cursor a page: one row less than {@link #pageRows()}, at least 1. */
    public void page(int direction) {
        move(direction * Math.max(1, pageRows - 1));
    }

    /** BEHAVIOR: the cursor goes to the first row. */
    public void home() {
        move(-rows.size());
    }

    /** BEHAVIOR: the cursor goes to the last row. */
    public void end() {
        move(rows.size());
    }

    /**
     * A character was typed.
     * <p>
     * BEHAVIOR: a shortcut ({@link LobbyKeys#rowOf}) of an existing row moves the cursor there and returns that
     * map; any other character, and a shortcut beyond the list, returns null and moves nothing.
     */
    public LobbyEntry choose(int codepoint) {
        int row = LobbyKeys.rowOf(codepoint);
        if (row < 0 || row >= rows.size()) {
            return null;
        }
        cursor = row;
        scrollToCursor();
        return rows.get(row);
    }

    private void scrollToCursor() {
        if (cursor < top) {
            top = cursor;
        } else if (cursor >= top + pageRows) {
            top = cursor - pageRows + 1;
        }
    }

    /**
     * Lay the panel out in a {@code viewW x viewH} view.
     * <p>
     * BEHAVIOR: clears {@code out}. A black panel with a white outline at {@link #MARGIN}: the heading, a line of
     * instructions, the column heads, the visible rows and a bottom line (the message when there is one, else
     * the rows shown). As many rows as fit are shown, starting at {@link #top()}, which is adjusted so the cursor
     * is visible.
     * BEHAVIOR: a row is its shortcut in the HUD colour, {@code *} when the map is active, the name, the number of
     * humans (active maps only) right-aligned under its head, and the map's title cut to the panel width; active
     * rows are white, idle ones in the old-message colour; the cursor row is white on a blue bar.
     * BEHAVIOR: every text is in {@link FontRole#KEY_LIST}.
     */
    public void layout(HudFrame out, int viewW, int viewH, TextMetrics m) {
        out.clear();
        int n = rows.size();
        int lineH = m.ascent() + m.descent() + 2;
        int head = MARGIN + MARGIN + 3 * lineH;
        int foot = MARGIN + lineH;
        int fit = Math.max(1, (viewH - 2 * MARGIN - head - foot) / lineH);
        pageRows = fit;
        scrollToCursor();
        top = Math.max(0, Math.min(top, Math.max(0, n - fit)));
        int shown = Math.min(fit, n - top);

        int keyW = m.width("[W]", 3);
        int starW = m.width("*", 1);
        int nameW = 0;
        int humansW = m.width(HEADER_HUMANS, HEADER_HUMANS.length());
        int titleW = m.width(HEADER_TITLE, HEADER_TITLE.length());
        for (int i = 0; i < n; i++) {
            LobbyEntry e = rows.get(i);
            nameW = Math.max(nameW, m.width(e.name(), e.name().length()));
            humansW = Math.max(humansW, m.width(humansText[i], humansText[i].length()));
            titleW = Math.max(titleW, m.width(e.title(), e.title().length()));
        }
        String instructions = "Up/Down and Enter choose, or press the key in [ ]. Esc quits.";
        int x0 = 2 * MARGIN;
        int starX = x0 + keyW + GAP / 2;
        int nameX = starX + starW + GAP / 2;
        int humansX = nameX + nameW + GAP;
        int titleX = humansX + humansW + GAP;
        int width = Math.max(titleX - x0 + titleW, Math.max(m.width(headingLine, headingLine.length()),
            m.width(instructions, instructions.length())));
        int maxWidth = Math.max(100, viewW - 4 * MARGIN);
        width = Math.min(width, maxWidth);
        int titleRoom = Math.max(0, x0 + width - titleX);
        int height = head + shown * lineH + foot;
        out.rect(MARGIN, MARGIN, width + 2 * MARGIN, height, true, HudColor.BLACK);
        out.rect(MARGIN, MARGIN, width + 2 * MARGIN, height, false, HudColor.WHITE);
        int y = MARGIN + MARGIN / 2 + m.ascent();
        put(out, x0, y, headingLine, HudColor.WHITE);
        put(out, x0, y + lineH, instructions, HudColor.HUD);
        y += 2 * lineH;
        put(out, nameX, y, "Name", HudColor.HUD);
        put(out, humansX + humansW - m.width(HEADER_HUMANS, HEADER_HUMANS.length()), y, HEADER_HUMANS, HudColor.HUD);
        put(out, titleX, y, HEADER_TITLE, HudColor.HUD);
        for (int v = 0; v < shown; v++) {
            int i = top + v;
            LobbyEntry e = rows.get(i);
            int ry = head + v * lineH + m.ascent();
            boolean cur = i == cursor;
            if (cur) {
                out.rect(MARGIN + 3, ry - m.ascent() - 1, width + 2 * MARGIN - 6, lineH, true, HudColor.EMPHASIZED);
            }
            int textColor = cur || e.active() ? HudColor.WHITE : HudColor.OLD_MESSAGE;
            put(out, x0, ry, keyText[i], cur ? HudColor.WHITE : HudColor.HUD);
            if (e.active()) {
                put(out, starX, ry, "*", HudColor.WHITE);
            }
            put(out, nameX, ry, e.name(), textColor);
            put(out, humansX + humansW - m.width(humansText[i], humansText[i].length()), ry, humansText[i], textColor);
            putClipped(out, titleX, ry, e.title(), titleRoom, textColor, m);
        }
        int fy = head + shown * lineH + MARGIN / 2 + m.ascent();
        if (!message.isEmpty()) {
            put(out, x0, fy, message, HudColor.WHITE);
        } else if (n > fit) {
            com.fulcrologic.jxpilot.client.game.CharBuf t = out.text(x0, fy, FontRole.KEY_LIST, HudColor.OLD_MESSAGE);
            if (t != null) {
                t.append("Maps ").appendInt(top + 1).append('-').appendInt(top + shown).append(" of ").appendInt(n)
                    .append(" (PageUp, PageDown)");
            }
        } else if (n == 0) {
            put(out, x0, fy, "Waiting for the list of maps...", HudColor.OLD_MESSAGE);
        }
    }

    private void put(HudFrame out, int x, int y, String s, int color) {
        if (s.isEmpty()) {
            return;
        }
        com.fulcrologic.jxpilot.client.game.CharBuf t = out.text(x, y, FontRole.KEY_LIST, color);
        if (t != null) {
            t.append(s);
        }
    }

    private void putClipped(HudFrame out, int x, int y, String s, int room, int color, TextMetrics m) {
        int len = s.length();
        while (len > 0 && m.width(s, len) > room) {
            len--;
        }
        if (len == 0) {
            return;
        }
        com.fulcrologic.jxpilot.client.game.CharBuf t = out.text(x, y, FontRole.KEY_LIST, color);
        if (t != null) {
            for (int i = 0; i < len; i++) {
                t.append(s.charAt(i));
            }
        }
    }
}
