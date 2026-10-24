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

import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.HudColor;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.net.PacketStats;

import java.util.TimeZone;
import java.util.function.LongSupplier;

import static com.fulcrologic.jxpilot.client.game.options.ClientOption.*;

/**
 * The per-frame look extras of the C client that are not part of the HUD layout proper, read live from the
 * {@link ClientOptions}:
 * <ul>
 *   <li>the packet meters ({@code packetSizeMeter}, {@code packetLossMeter}, {@code packetDropMeter},
 *   {@code packetLagMeter}; {@code painthud.c} {@code Paint_meters}) over the connection's {@link PacketStats};</li>
 *   <li>the clock at the top right of the score window ({@code clock}, {@code clockAMPM}; {@code paint.c}
 *   {@code Paint_clock});</li>
 *   <li>the bell in the last 10 seconds of game time ({@code paintdata.c} {@code Handle_time_left});</li>
 *   <li>the window title flip ({@code titleFlip}; {@code paint.c} {@code Paint_frame}).</li>
 * </ul>
 * The wall clock and time zone are injected. Allocation free.
 */
public final class LookFrame {
    /** C {@code TITLE_DELAY}: frames between two window title flips. */
    public static final int TITLE_DELAY = 500;
    /** {@link #title} results: keep the title, show the normal one, show the copyright one. */
    public static final int TITLE_KEEP = 0;
    public static final int TITLE_NORMAL = 1;
    public static final int TITLE_COPYRIGHT = 2;
    /** C {@code Paint_clock}'s border around the text. */
    static final int CLOCK_BORDER = 3;

    private final ClientOptions options;
    private final LongSupplier millis;
    private final TimeZone zone;
    private final CharBuf meterTitle = new CharBuf(16);
    private final CharBuf clock = new CharBuf(16);
    private long clockLoops = Long.MIN_VALUE;
    private int clockVersion = -1;
    private int lastTimeLeft = -1;
    private long titleLoops = -1;
    private boolean copyright;

    public LookFrame(ClientOptions options) {
        this(options, System::currentTimeMillis, TimeZone.getDefault());
    }

    /** @param millis wall clock (ms since the epoch); @param zone the local time zone of the clock */
    public LookFrame(ClientOptions options, LongSupplier millis, TimeZone zone) {
        this.options = options;
        this.millis = millis;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ packet meters

    /**
     * The packet meters of C {@code Paint_meters}, each with {@link Hud#meter} at x offset -10 (right aligned)
     * in the view of {@code viewW} view units.
     * <p>
     * BEHAVIOR: with its option on: "Packet" at y 80, size of 4096 (sizes above 4096 full); "Loss" at y 100, loss
     * of the rate (C {@code FPS}); "Drop" at y 120, drop of the rate; "Lag" at y 140, lag (at most the rate) of the
     * rate. Off meters draw nothing.
     */
    public void packetMeters(HudFrame out, Hud hud, int viewW, PacketStats stats, TextMetrics m) {
        int rate = stats.rate();
        if (options.flag(PACKET_SIZE_METER)) {
            hud.meter(out, viewW, -10, 80, meterTitle.clear().append("Packet"), Math.min(stats.size(), 4096), 4096, m);
        }
        if (options.flag(PACKET_LOSS_METER)) {
            hud.meter(out, viewW, -10, 100, meterTitle.clear().append("Loss"), stats.loss(), rate, m);
        }
        if (options.flag(PACKET_DROP_METER)) {
            hud.meter(out, viewW, -10, 120, meterTitle.clear().append("Drop"), stats.drop(), rate, m);
        }
        if (options.flag(PACKET_LAG_METER)) {
            hud.meter(out, viewW, -10, 140, meterTitle.clear().append("Lag"), Math.min(stats.lag(), rate), rate, m);
        }
    }

    // ------------------------------------------------------------------ clock

    /**
     * C {@code Paint_clock} in the score window at ({@code x0}, {@code y0}), {@code width} wide.
     * <p>
     * BEHAVIOR: without {@code clock} nothing. Otherwise a WINDOW-coloured box of the text width plus 2 x 3 by
     * ascent + descent + 3 at the window's top right, and the text in shadowed white (SCORE, in the score list font, as C's {@code scoreListFont}) at
     * {@code x0 + width - (textWidth + 3)}, baseline {@code y0 + ascent + 4}.
     * BEHAVIOR: the text is recomputed ({@link #clockText}) on the first call, when the options changed, when
     * {@code loops} did not advance, or when {@code fps << 5} frames passed since the last time (C's half-minute
     * refresh); otherwise the previous text is drawn.
     */
    public void clock(HudFrame out, int x0, int y0, int width, TextMetrics m, long loops, int fps) {
        if (!options.flag(CLOCK)) {
            return;
        }
        if (clockVersion != options.version() || loops <= clockLoops || loops - clockLoops >= (long) fps << 5) {
            clockVersion = options.version();
            clockLoops = loops;
            long now = millis.getAsLong();
            clockText(clock.clear(), now + zone.getOffset(now), options.flag(CLOCK_AMPM));
        }
        int w = m.width(clock, clock.length());
        int height = m.ascent() + m.descent() + 3;
        out.rect(x0 + width - (w + 2 * CLOCK_BORDER), y0, w + 2 * CLOCK_BORDER, height, true, HudColor.WINDOW);
        CharBuf t = out.text(x0 + width - (w + CLOCK_BORDER), y0 + m.ascent() + 4, FontRole.SCORE_LIST, HudColor.SCORE);
        if (t != null) {
            t.append(clock);
        }
    }

    /**
     * The clock text of C {@code Paint_clock} for a local time of {@code localMillis} (ms since the epoch in the
     * local zone).
     * <p>
     * BEHAVIOR: 24-hour: "HH:MM" with the minute rounded up to the next one (59 minutes carry into the hour, 23
     * hours into 0), as C does. AM/PM: "%2d:%02d" of the unrounded hour and minute plus "AM", or "PM" with the
     * hour taken modulo 12 when it is above 12 (C's test: noon is "12:00AM", midnight " 0:00AM").
     */
    static CharBuf clockText(CharBuf out, long localMillis, boolean ampm) {
        int minutes = (int) Math.floorMod(Math.floorDiv(localMillis, 60_000L), 1440L);
        int hour = minutes / 60;
        int minute = minutes % 60;
        if (!ampm) {
            if (minute++ == 59) {
                minute = 0;
                if (hour++ == 23) {
                    hour = 0;
                }
            }
            two(out, hour).append(':');
            return two(out, minute);
        }
        char ap = 'A';
        if (hour > 12) {
            ap = 'P';
            hour %= 12;
        }
        if (hour < 10) {
            out.append(' ');
        }
        out.appendInt(hour).append(':');
        return two(out, minute).append(ap).append('M');
    }

    private static CharBuf two(CharBuf out, int v) {
        if (v < 10) {
            out.append('0');
        }
        return out.appendInt(v);
    }

    // ------------------------------------------------------------------ bell

    /**
     * C {@code Handle_time_left}'s {@code XBell}: call once per frame with the game time left in seconds (-1 when
     * the server never said).
     * <p>
     * BEHAVIOR: true when the value changed to one in 0..9 that is lower than the previous one, or to 0 (each new
     * second of the last ten rings once); false otherwise, and always for -1 (which keeps the previous value).
     */
    public boolean bell(int timeLeft) {
        if (timeLeft < 0 || timeLeft == lastTimeLeft) {
            return false;
        }
        int prev = lastTimeLeft;
        lastTimeLeft = timeLeft;
        return timeLeft < 10 && (prev > timeLeft || timeLeft == 0);
    }

    // ------------------------------------------------------------------ title flip

    /**
     * C {@code Paint_frame}'s title flip at frame {@code loops}.
     * <p>
     * BEHAVIOR: with {@code titleFlip}: on a new positive frame number that is a multiple of {@link #TITLE_DELAY}
     * the title toggles, first to the copyright one ({@link #TITLE_COPYRIGHT}), then back ({@link #TITLE_NORMAL});
     * any other frame {@link #TITLE_KEEP}.
     * BEHAVIOR: without {@code titleFlip}: {@link #TITLE_NORMAL} once if the copyright title is showing (the option
     * was turned off), else {@link #TITLE_KEEP}.
     */
    public int title(long loops) {
        if (!options.flag(TITLE_FLIP)) {
            if (copyright) {
                copyright = false;
                return TITLE_NORMAL;
            }
            return TITLE_KEEP;
        }
        if (loops <= 0 || loops == titleLoops || loops % TITLE_DELAY != 0) {
            return TITLE_KEEP;
        }
        titleLoops = loops;
        copyright = !copyright;
        return copyright ? TITLE_COPYRIGHT : TITLE_NORMAL;
    }
}
