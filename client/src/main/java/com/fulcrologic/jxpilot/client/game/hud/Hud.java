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

import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.MessageLog;
import com.fulcrologic.jxpilot.client.game.ScoreObjects;
import com.fulcrologic.jxpilot.client.game.Scoreboard;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.render.WinScale;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Records;

/**
 * HUD layout, a port of {@code Paint_HUD}, {@code Paint_meter} and {@code Paint_messages}
 * ({@code src/client/painthud.c}), the talk window geometry ({@code src/client/talk.c}) and the score
 * list lines ({@code Paint_score_start}/{@code Paint_score_entry}, {@code src/client/paint.c}).
 * Produces a {@link HudFrame} draw list in window pixels (y down). Timers count original
 * XPilot frames ({@code gameFps}). Pure apart from its own counters; allocation free.
 * <p>
 * Zoom ({@link #zoom}, C's {@code 1 / scaleFactor}) works as in C: the ship HUD and the meters are laid out
 * in the extended view ({@code ext_view_width = (int)(viewW / zoom + 0.5)}), the coordinates and sizes C
 * wraps in {@code WINSCALE} (directly or through {@code Rectangle_add}/{@code Segment_add}) are scaled with
 * {@link WinScale#of}, and font offsets (ascent, descent, text widths) are added unscaled afterwards.
 * Messages, the talk window and the status line are in plain window pixels, as in C.
 */
public final class Hud {
    /** C {@code HUD_SIZE}: half size of the HUD lines. */
    public static final int HUD_SIZE = 90;
    /** C {@code HUD_OFFSET}. */
    public static final int HUD_OFFSET = 20;
    /** C {@code FUEL_GAUGE_OFFSET}. */
    public static final int FUEL_GAUGE_OFFSET = 6;
    /** C {@code HUD_FUEL_GAUGE_SIZE} = 2*(HUD_SIZE-HUD_OFFSET-FUEL_GAUGE_OFFSET) = 128. */
    public static final int HUD_FUEL_GAUGE_SIZE = 2 * (HUD_SIZE - HUD_OFFSET - FUEL_GAUGE_OFFSET);
    /** Paint_HUD's BORDER. */
    public static final int BORDER = 3;
    /** C {@code METER_WIDTH}. */
    public static final int METER_WIDTH = 200;
    /** C {@code METER_HEIGHT}. */
    public static final int METER_HEIGHT = 8;
    /** Paint_meter's BORDER. */
    public static final int METER_BORDER = 5;
    /** Paint_messages' BORDER. */
    public static final int MESSAGE_BORDER = 10;
    /** C {@code SCORE_BORDER}. */
    public static final int SCORE_BORDER = 6;
    /** C {@code TALK_OUTSIDE_BORDER}. */
    public static final int TALK_OUTSIDE_BORDER = 2;
    /** C {@code TALK_INSIDE_BORDER}. */
    public static final int TALK_INSIDE_BORDER = 3;
    /** C {@code TALK_WINDOW_X} = 50 - TALK_OUTSIDE_BORDER. */
    public static final int TALK_WINDOW_X = 50 - TALK_OUTSIDE_BORDER;
    /** C {@code FUEL_NOTIFY} = 3 * FPS frames. */
    public static final int FUEL_NOTIFY_SECONDS = 3;

    // ---- settings (C client resources, defaults from default.c)
    /** {@code speedFactHUD}: HUD moves against the velocity (0 = fixed). */
    public double hudMoveFact = 0.0;
    /** {@code speedFactPTR}: length factor of the speed pointer (0 = off). */
    public double ptrMoveFact = 0.0;
    /** {@code showHUD}. */
    public boolean showHud = true;
    /** {@code horizontalHUDLine}. */
    public boolean horizontalLines = true;
    /** {@code verticalHUDLine}. */
    public boolean verticalLines = false;
    /** {@code fuelGauge}. */
    public boolean fuelGauge = true;
    /** {@code fuelMeter}. */
    public boolean fuelMeter = false;
    /** {@code powerMeter} (C default off; shown anyway for {@link #CONTROL_DELAY} after a settings change). */
    public boolean powerMeter = false;
    /** {@code turnSpeedMeter} (C default off). */
    public boolean turnSpeedMeter = false;
    /** C {@code CONTROL_DELAY}: frames the power/turn speed meters stay after a settings key. */
    public static final int CONTROL_DELAY = 100;
    /** {@code fuelNotify} (fuelLevel3). */
    public int fuelNotify = 500;
    /** {@code fuelWarning} (fuelLevel2). */
    public int fuelWarning = 200;
    /** {@code fuelCritical} (fuelLevel1). */
    public int fuelCritical = 100;
    /** Game messages shown (C SHOW_MESSAGES). */
    public boolean showMessages = true;
    /** Zoom, C {@code 1 / scaleFactor} (1 = unscaled). */
    public double zoom = 1.0;

    // ---- state
    private double loops;
    private double fuelCount;
    private double controlCount;
    private float lastFuel = -1;
    private boolean wasVisible;
    private final CharBuf scratch = new CharBuf(32);

    /** Original frames elapsed (C {@code loops}). */
    public long loops() { return (long) loops; }

    /** Remaining fuel notification frames (C {@code fuelCount}). */
    public double fuelCount() { return fuelCount; }

    /**
     * Advance the counters by {@code frames} original frames.
     * <p>
     * BEHAVIOR: loops advances; fuel notify counts down to 0, and restarts at 3 * fps frames when the
     * fuel went up while the own ship was visible in the previous frame (refuelling; C paintdata.c gates
     * on {@code selfVisible}, so the refill at respawn does not notify) or when {@link #notifyFuel(double)}
     * is called (fuel key).
     *
     * @param selfVisible the own ship is drawn this frame (it exists and is not dead)
     */
    public void advance(double frames, float fuel, double fps, boolean selfVisible) {
        loops += frames;
        fuelCount = Math.max(0, fuelCount - frames);
        controlCount = Math.max(0, controlCount - frames);
        if (lastFuel >= 0 && fuel > lastFuel && wasVisible) {
            fuelCount = FUEL_NOTIFY_SECONDS * fps;
        }
        lastFuel = fuel;
        wasVisible = selfVisible;
    }

    /** C {@code control_count = CONTROL_DELAY} (power, turn speed or swap key): show the control meters. */
    public void showControls() {
        controlCount = CONTROL_DELAY;
    }

    /** Frames the control meters still show (C {@code control_count}). */
    public double controlCount() { return controlCount; }

    /** C {@code Key_press_fuel}: show the fuel for {@link #FUEL_NOTIFY_SECONDS}. */
    public void notifyFuel(double fps) {
        fuelCount = FUEL_NOTIFY_SECONDS * fps;
    }

    /**
     * Build the whole HUD for one frame.
     * <p>
     * BEHAVIOR: clears {@code out}; draws the ship HUD and meters whenever the own ship exists, also while
     * dead (C paints them every frame); only the speed pointer needs a visible (not dead) ship (C
     * {@code selfVisible}); messages, the talk window (when open) and the connection status (when not
     * playing) always.
     */
    public void build(HudFrame out, ClientGame g, TalkLine talk, int viewW, int viewH, TextMetrics m,
                      double frames) {
        out.clear();
        ClientWorld w = g.world();
        float fuel = w.hasSelf() ? w.selfFuel() : 0;
        boolean visible = w.hasSelf() && (w.selfFlags() & Records.ShipFlags.DEAD) == 0;
        advance(frames, fuel, g.gameFps(), visible);
        if (w.hasSelf()) {
            shipHud(out, w, viewW, viewH, m, g, visible);
        }
        messages(out, g.messages(), viewH, m, g.gameFps());
        if (talk.isOpen()) {
            talk(out, talk, viewW, viewH, m);
        }
        if (g.state() != ConnectionState.PLAYING) {
            CharBuf s = g.status();
            int width = m.width(s, s.length());
            CharBuf t = out.text((viewW - width) / 2f, viewH / 2f, HudColor.WHITE);
            if (t != null) {
                t.append(s);
            }
        }
    }

    private void shipHud(HudFrame out, ClientWorld w, int viewW, int viewH, TextMetrics m, ClientGame g,
                         boolean visible) {
        float vx = w.selfVx();
        float vy = w.selfVy();
        int ew = extent(viewW);
        int eh = extent(viewH);
        if (visible) {
            speedPointer(out, ew, eh, vx, vy);
        }
        float fuel = w.selfFuel();
        float fuelMax = w.selfFuelMax();
        if (fuelMeter) {
            scratch.clear().append("Fuel");
            meter(out, ew, -10, 20, scratch, (int) fuel, (int) fuelMax, m);
        }
        meters(out, w, ew, eh, m);
        if (!showHud) {
            return;
        }
        int hx = hudX(ew, vx);
        int hy = hudY(eh, vy);
        hudLines(out, hx, hy);
        boolean didFuel = fuelText(out, hx, hy, fuel, w.selfNumTanks(), w.selfCurrentTank(), m);
        scoreStack(out, hx, hy, g.scoreObjects(), didFuel, m);
        if (fuelGaugeVisible(fuel)) {
            fuelGauge(out, hx, hy, fuel, fuelMax);
        }
    }

    /**
     * The rest of C {@code Paint_meters}.
     * <p>
     * BEHAVIOR: Power (-10, 40) and Turnspeed (-10, 60) from the server's SELF values, shown with their
     * meter option or while {@link #controlCount()} &gt; 0; at x = (viewW - 300) / 2 - 32, y = 2 * viewH / 3
     * + 0/20/40/60/80: "Thrust Left", "Shields Left", "Phasing left" (each iff its max &gt; 0),
     * "Self destructing" (destruct / 150, iff destruct &gt; 0) and "SHUTDOWN" (count / delay, iff count &gt;= 0).
     */
    void meters(HudFrame out, ClientWorld w, int ew, int eh, TextMetrics m) {
        if (powerMeter || controlCount > 0) {
            scratch.clear().append("Power");
            meter(out, ew, -10, 40, scratch, w.selfPower(), (int) Const.MAX_PLAYER_POWER, m);
        }
        if (turnSpeedMeter || controlCount > 0) {
            scratch.clear().append("Turnspeed");
            meter(out, ew, -10, 60, scratch, w.selfTurnSpeed(), (int) Const.MAX_PLAYER_TURNSPEED, m);
        }
        int x = (ew - 300) / 2 - 32;
        int y = 2 * eh / 3;
        if (w.thrustMax() > 0) {
            scratch.clear().append("Thrust Left");
            meter(out, ew, x, y, scratch, Math.min(w.thrustLeft(), w.thrustMax()), w.thrustMax(), m);
        }
        if (w.shieldMax() > 0) {
            scratch.clear().append("Shields Left");
            meter(out, ew, x, y + 20, scratch, Math.min(w.shieldLeft(), w.shieldMax()), w.shieldMax(), m);
        }
        if (w.phasingMax() > 0) {
            scratch.clear().append("Phasing left");
            meter(out, ew, x, y + 40, scratch, Math.min(w.phasingLeft(), w.phasingMax()), w.phasingMax(), m);
        }
        if (w.destruct() > 0) {
            scratch.clear().append("Self destructing");
            meter(out, ew, x, y + 60, scratch, w.destruct(), 150, m);
        }
        if (w.shutdownCount() >= 0) {
            scratch.clear().append("SHUTDOWN");
            meter(out, ew, x, y + 80, scratch, w.shutdownCount(), w.shutdownDelay(), m);
        }
    }

    private int ws(int n) {
        return WinScale.of(n, zoom);
    }

    /** BEHAVIOR: C {@code ext_view_width}: a window size in view units, (int)(size / zoom + 0.5). */
    public int extent(int windowSize) {
        return (int) (windowSize / zoom + 0.5);
    }

    /** BEHAVIOR: C hud_pos_x = (int)(viewW/2 - hudMoveFact * vx) in view units. */
    public int hudX(int viewW, float vx) {
        return (int) (viewW / 2 - hudMoveFact * vx);
    }

    /** BEHAVIOR: C hud_pos_y = (int)(viewH/2 + hudMoveFact * vy) (window y grows down, world y up). */
    public int hudY(int viewH, float vy) {
        return (int) (viewH / 2 + hudMoveFact * vy);
    }

    /**
     * BEHAVIOR: a HUD-coloured segment from the view centre against the velocity, iff ptrMoveFact != 0 and
     * moving; view units, every end scaled (C Segment_add).
     */
    void speedPointer(HudFrame out, int viewW, int viewH, float vx, float vy) {
        if (ptrMoveFact != 0.0 && (vx != 0 || vy != 0)) {
            out.line(ws(viewW / 2), ws(viewH / 2), ws((int) (viewW / 2 - ptrMoveFact * vx)),
                ws((int) (viewH / 2 + ptrMoveFact * vy)), HudFrame.SOLID, HudColor.HUD);
        }
    }

    /**
     * BEHAVIOR: dashed horizontal lines at hy -+ (HUD_SIZE - HUD_OFFSET) spanning hx -+ HUD_SIZE when
     * enabled; dashed vertical lines at hx -+ (HUD_SIZE - HUD_OFFSET) spanning hy -+ HUD_SIZE when enabled.
     */
    void hudLines(HudFrame out, int hx, int hy) {
        int in = HUD_SIZE - HUD_OFFSET;
        if (horizontalLines) {
            out.line(ws(hx - HUD_SIZE), ws(hy - in), ws(hx + HUD_SIZE), ws(hy - in), HudFrame.DASHED, HudColor.HUD);
            out.line(ws(hx - HUD_SIZE), ws(hy + in), ws(hx + HUD_SIZE), ws(hy + in), HudFrame.DASHED, HudColor.HUD);
        }
        if (verticalLines) {
            out.line(ws(hx - in), ws(hy - HUD_SIZE), ws(hx - in), ws(hy + HUD_SIZE), HudFrame.DASHED, HudColor.HUD);
            out.line(ws(hx + in), ws(hy - HUD_SIZE), ws(hx + in), ws(hy + HUD_SIZE), HudFrame.DASHED, HudColor.HUD);
        }
    }

    /**
     * The fuel number below-right of the HUD ("%04d") and, with tanks, the tank indicator ("M " for the
     * main tank, else "T&lt;n&gt;") one line lower.
     * <p>
     * BEHAVIOR: shown iff the fuel notification is running or fuel &lt; fuelNotify; returns whether shown.
     */
    boolean fuelText(HudFrame out, int hx, int hy, float fuel, int numTanks, int currentTank, TextMetrics m) {
        if (!(fuelCount > 0 || fuel < fuelNotify)) {
            return false;
        }
        int x = ws(hx + HUD_SIZE - HUD_OFFSET + BORDER);
        int y = ws(hy + HUD_SIZE - HUD_OFFSET + BORDER);
        CharBuf t = out.text(x, y + m.ascent(), HudColor.HUD);
        if (t != null) {
            t.appendInt((int) fuel, 4, '0');
        }
        if (numTanks > 0) {
            CharBuf k = out.text(x, y + m.descent() + 2 * m.ascent(), HudColor.HUD);
            if (k != null) {
                if (currentTank == 0) {
                    k.append("M ");
                } else {
                    k.append('T').appendInt(currentTank);
                }
            }
        }
        return true;
    }

    /**
     * C's fuel gauge visibility.
     * <p>
     * BEHAVIOR: hidden when the gauge is off; shown while the notification runs; else only below
     * fuelNotify: steady above fuelWarning, blinking (loops % 8 &lt; 4) between fuelCritical and
     * fuelWarning, fast blinking (loops % 4 &lt; 2) below fuelCritical; exactly at fuelCritical (or
     * fuelWarning) hidden, as in C.
     */
    boolean fuelGaugeVisible(float fuel) {
        if (!fuelGauge) {
            return false;
        }
        long l = loops();
        return fuelCount > 0
            || fuel < fuelNotify
            && (fuel < fuelCritical && l % 4 < 2
                || fuel < fuelWarning && fuel > fuelCritical && l % 8 < 4
                || fuel > fuelWarning);
    }

    /**
     * BEHAVIOR: outline at (hx+75, hy-65) size 11 x 131, and a fill of height
     * size = (int)(128 * fuel / fuelMax) at (hx+77, hy-63+128-size) width 8 (C coordinates).
     */
    void fuelGauge(HudFrame out, int hx, int hy, float fuel, float fuelMax) {
        int gx = hx + HUD_SIZE - HUD_OFFSET + FUEL_GAUGE_OFFSET;
        int gy = hy - HUD_SIZE + HUD_OFFSET + FUEL_GAUGE_OFFSET;
        int gw = HUD_OFFSET - 2 * FUEL_GAUGE_OFFSET;
        out.rect(ws(gx) - 1, ws(gy) - 1, ws(gw) + 3, ws(HUD_FUEL_GAUGE_SIZE) + 3, false, HudColor.HUD);
        int size = fuelMax > 0 ? (int) (HUD_FUEL_GAUGE_SIZE * fuel / fuelMax) : 0;
        out.rect(ws(gx) + 1, ws(gy + HUD_FUEL_GAUGE_SIZE - size) + 1, ws(gw), ws(size), true, HudColor.HUD);
    }

    /**
     * The HUD score stack (C painthud.c): every live score object with a HUD text, oldest first, centred
     * under the HUD.
     * <p>
     * BEHAVIOR: line j at baseline hy + 73 + ascent + j * (ascent + descent), j counting the texts drawn;
     * the first text starts one line lower when it is wider than the HUD inner width (140) and the fuel
     * text or vertical lines occupy the space.
     */
    void scoreStack(HudFrame out, int hx, int hy, ScoreObjects so, boolean didFuel, TextMetrics m) {
        int j = 0;
        for (int i = 0; i < ScoreObjects.MAX_SCORE_OBJECTS; i++) {
            int s = so.hudOrder(i);
            CharBuf msg = so.hudText(s);
            if (msg.length() == 0) {
                continue;
            }
            int width = m.width(msg, msg.length());
            if (j == 0 && width > ws(2 * HUD_SIZE - HUD_OFFSET * 2) && (didFuel || verticalLines)) {
                j++;
            }
            CharBuf t = out.text(ws(hx) - width / 2, ws(hy + HUD_SIZE - HUD_OFFSET + BORDER) + m.ascent()
                + j * (m.ascent() + m.descent()), HudColor.HUD);
            if (t != null) {
                t.append(msg);
            }
            j++;
        }
    }

    /**
     * C {@code Paint_meter}.
     * <p>
     * BEHAVIOR: xoff &gt;= 0 places the meter at xoff with the title to its right; negative xoff places
     * it at viewW - (METER_WIDTH - xoff) with the title to its left. viewW, xoff and y are view units;
     * geometry is scaled (C WINSCALE / Rectangle_add / Segment_add), the title offsets are not.
     * BEHAVIOR: a red bar (x+2, y+2, (METER_WIDTH-3)*val/max, METER_HEIGHT-3), a white outline, five
     * white scale ticks, and the title at baseline y + (ascent + METER_HEIGHT)/2. max 0 counts as 1.
     */
    public void meter(HudFrame out, int viewW, int xoff, int y, CharSequence title, int val, int max, TextMetrics m) {
        int x;
        int xstr;
        int tw = m.width(title, title.length());
        if (xoff >= 0) {
            x = xoff;
            xstr = ws(x + METER_WIDTH) + METER_BORDER;
        } else {
            x = viewW - (METER_WIDTH - xoff);
            xstr = ws(x) - (METER_BORDER + tw);
        }
        out.rect(ws(x + 2), ws(y + 2), ws(((METER_WIDTH - 3) * val) / (max != 0 ? max : 1)), ws(METER_HEIGHT - 3), true,
            HudColor.RED);
        out.rect(ws(x), ws(y), ws(METER_WIDTH), ws(METER_HEIGHT), false, HudColor.WHITE);
        tick(out, x, y - 4, y + METER_HEIGHT + 4);
        tick(out, x + METER_WIDTH, y - 4, y + METER_HEIGHT + 4);
        tick(out, x + METER_WIDTH / 2, y - 3, y + METER_HEIGHT + 3);
        tick(out, x + METER_WIDTH / 4, y - 1, y + METER_HEIGHT + 1);
        tick(out, x + 3 * METER_WIDTH / 4, y - 1, y + METER_HEIGHT + 1);
        CharBuf t = out.text(xstr, ws(y) + (m.ascent() + METER_HEIGHT) / 2, HudColor.WHITE);
        if (t != null) {
            t.append(title);
        }
    }

    private void tick(HudFrame out, int x, int y0, int y1) {
        out.line(ws(x), ws(y0), ws(x), ws(y1), HudFrame.SOLID, HudColor.WHITE);
    }

    /**
     * C {@code Paint_messages}.
     * <p>
     * BEHAVIOR: talk messages from the top (first baseline MESSAGE_BORDER + ascent), game messages from
     * the bottom up (first baseline viewH - descent - MESSAGE_BORDER), newest nearest the edge, line
     * spacing ascent + descent + 1, x = MESSAGE_BORDER; game messages only when showMessages.
     * BEHAVIOR: each shows {@link MessageLog#visibleChars} characters, RED while flashing, else OLD_MESSAGE.
     */
    void messages(HudFrame out, MessageLog log, int viewH, TextMetrics m, double fps) {
        int spacing = m.ascent() + m.descent() + 1;
        int y = MESSAGE_BORDER + m.ascent();
        for (int i = 0; i < log.talkCount(); i++) {
            message(out, log, log.talk(i), y, fps);
            y += spacing;
        }
        if (!showMessages) {
            return;
        }
        y = viewH - m.descent() - MESSAGE_BORDER;
        for (int i = 0; i < log.gameCount(); i++) {
            message(out, log, log.game(i), y, fps);
            y -= spacing;
        }
    }

    private static void message(HudFrame out, MessageLog log, MessageLog.Entry e, int y, double fps) {
        CharBuf t = out.text(MESSAGE_BORDER, y, e.flashing() ? HudColor.RED : HudColor.OLD_MESSAGE);
        if (t != null) {
            t.append(e.text());
            out.setTextLength(log.visibleChars(e, fps));
        }
    }

    /**
     * The talk window (C talk.c geometry).
     * <p>
     * BEHAVIOR: text height h = ascent + descent; window height H = h + 2*TALK_INSIDE_BORDER at
     * x = TALK_WINDOW_X, y = viewH*3/4 - H/2, inner width W = viewW - 2*(TALK_WINDOW_X + TALK_OUTSIDE_BORDER).
     * Emits a black filled inner rect, a white outline around the border, the text at
     * (inner x + TALK_INSIDE_BORDER, inner y + TALK_INSIDE_BORDER + ascent) and a cursor line at
     * the width of the text before the cursor.
     */
    void talk(HudFrame out, TalkLine talk, int viewW, int viewH, TextMetrics m) {
        int h = m.ascent() + m.descent();
        int wh = h + 2 * TALK_INSIDE_BORDER;
        int wy = viewH * 3 / 4 - wh / 2;
        int ww = talkWindowWidth(viewW);
        int ix = TALK_WINDOW_X + TALK_OUTSIDE_BORDER;
        int iy = wy + TALK_OUTSIDE_BORDER;
        out.rect(ix, iy, ww, wh, true, HudColor.BLACK);
        out.rect(TALK_WINDOW_X, wy, ww + 2 * TALK_OUTSIDE_BORDER, wh + 2 * TALK_OUTSIDE_BORDER, false, HudColor.WHITE);
        int tx = ix + TALK_INSIDE_BORDER;
        CharBuf t = out.text(tx, iy + TALK_INSIDE_BORDER + m.ascent(), HudColor.WHITE);
        if (t != null) {
            t.append(talk.text());
        }
        int cx = tx + m.width(talk.text(), talk.cursor());
        out.line(cx, iy + TALK_INSIDE_BORDER, cx, iy + TALK_INSIDE_BORDER + h, HudFrame.SOLID, HudColor.WHITE);
    }

    /** BEHAVIOR: C {@code TALK_WINDOW_WIDTH} = viewW - 2 * (TALK_WINDOW_X + TALK_OUTSIDE_BORDER). */
    public static int talkWindowWidth(int viewW) {
        return viewW - 2 * (TALK_WINDOW_X + TALK_OUTSIDE_BORDER);
    }

    /**
     * The score list panel at (x0, y0) of the given width (C draws it in its own window).
     * <p>
     * BEHAVIOR: heading at baseline y0 + SCORE_BORDER + ascent with a line under it; entry n at baseline
     * y0 + 2*SCORE_BORDER + ascent + spacing*(n+1), spacing = ascent + descent + 3; the best row
     * underlined; dimmed rows in SCORE_DIM.
     */
    public void scoreboard(HudFrame out, Scoreboard sb, int x0, int y0, int width, TextMetrics m) {
        int spacing = m.ascent() + m.descent() + 3;
        int headY = y0 + SCORE_BORDER + m.ascent();
        CharBuf h = out.text(x0 + SCORE_BORDER, headY, HudColor.SCORE);
        if (h != null) {
            h.append(sb.heading());
        }
        out.line(x0 + SCORE_BORDER, headY, x0 + width - SCORE_BORDER, headY, HudFrame.SOLID, HudColor.SCORE);
        int first = y0 + 2 * SCORE_BORDER + m.ascent() + spacing;
        for (int r = 0; r < sb.rowCount(); r++) {
            int y = first + spacing * sb.rowLine(r);
            CharBuf t = out.text(x0 + SCORE_BORDER, y, sb.dimmed(r) ? HudColor.SCORE_DIM : HudColor.SCORE);
            if (t != null) {
                t.append(sb.label(r));
            }
            if (r == sb.bestRow()) {
                out.line(x0 + SCORE_BORDER, y, x0 + width - SCORE_BORDER, y, HudFrame.SOLID, HudColor.SCORE);
            }
        }
    }
}
