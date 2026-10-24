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
package com.fulcrologic.jxpilot.client.sound;

import com.fulcrologic.jxpilot.client.game.SoundQueue;
import com.fulcrologic.jxpilot.common.SoundEvent;

/**
 * Plays the server's sound events (C {@code Handle_audio}) on an {@link AudioDevice}: each event's clip from
 * the {@link SoundMap}, at gain {@link #gain}(volume, maxVolume). The server has already attenuated the
 * volume by distance (C {@code sound_play_sensors}); like C the client only caps it at {@code maxVolume}.
 * <p>
 * Voices are fixed: each loop clip owns one voice, the rest play one-shots. A loop event (thrust, which C
 * sends every frame while thrusting) starts its loop or keeps it going for the hold time ({@link #setHold}:
 * 2.5 game frames, or 1.5 snapshot intervals when snapshots come less often); {@link #update} stops it when no
 * new event came. A one-shot takes a free voice, else the quietest
 * (then oldest) one-shot voice. Two events of the same clip in the same frame share one voice at the louder
 * gain. Pure logic over the device; allocation free per event and per frame.
 */
public final class SoundPlayer {
    /** Default voices: OpenAL Soft offers far more, 16 is plenty for XPilot's event rate. */
    public static final int VOICES = 16;
    /** A loop keeps playing this many game frames after its last event (C sends one per frame)... */
    public static final double LOOP_HOLD_FRAMES = 2.5;
    /** ...or this many intervals between snapshots, when those arrive less often (snapshotDivisor, a paused C player). */
    public static final double LOOP_HOLD_ARRIVALS = 1.5;
    /** The clip that stands in for the X bell ({@link #bell}). */
    public static final String BELL_CLIP = "blip.wav";
    /** C's default {@code maxVolume} resource. */
    public static final int DEFAULT_MAX_VOLUME = 100;
    /** Largest {@code maxVolume} C's configure menu accepts. */
    public static final int MAX_MAX_VOLUME = 255;

    private final AudioDevice dev;
    private final SoundMap map;
    /** Device handle of each map clip. */
    private final int[] handle;
    /** Reserved voice of each loop clip, -1 for one-shot clips. */
    private final int[] loopVoice;
    /** First one-shot voice (voices below it belong to loops). */
    private final int firstShot;
    /** Map clip index of {@link #BELL_CLIP}, -1 when the map has no such clip. */
    private final int bellClip;

    // per voice (voiceClip and started: one-shot voices only; a voice that is not playing matches nothing)
    private final int[] voiceClip;
    private final long[] started;
    private final float[] gain;
    private final boolean[] looping;
    private final long[] loopUntil;

    private int maxVolume = DEFAULT_MAX_VOLUME;
    private boolean muted;
    private long holdNanos = (long) (LOOP_HOLD_FRAMES * 1e9 / 14);
    private long cursor;

    /**
     * @param handles device handle per map clip ({@link AudioDevice#load} results)
     * @throws IllegalArgumentException when the loops leave no voice for one-shots
     */
    public SoundPlayer(AudioDevice dev, SoundMap map, int[] handles) {
        this.dev = dev;
        this.map = map;
        this.handle = handles.clone();
        int n = dev.voices();
        loopVoice = new int[map.clipCount()];
        int loops = 0;
        for (int c = 0; c < map.clipCount(); c++) {
            loopVoice[c] = map.loops(c) ? loops++ : -1;
        }
        if (loops >= n) {
            throw new IllegalArgumentException(loops + " loop clips need more than " + n + " voices");
        }
        firstShot = loops;
        int bc = -1;
        for (int c = 0; c < map.clipCount(); c++) {
            if (map.clipName(c).equals(BELL_CLIP)) {
                bc = c;
            }
        }
        bellClip = bc;
        voiceClip = new int[n];
        started = new long[n];
        gain = new float[n];
        looping = new boolean[n];
        loopUntil = new long[n];
    }

    /**
     * C {@code MIN(volume, maxVolume)} as an OpenAL gain.
     * <p>
     * BEHAVIOR: {@code min(volume, maxVolume) / 100}, clamped to 0..1 (a volume above 100 plays at full gain,
     * a negative one silently).
     */
    public static float gain(int volume, int maxVolume) {
        int v = Math.min(volume, maxVolume);
        return Math.max(0, Math.min(SoundEvent.MAX_VOLUME, v)) / (float) SoundEvent.MAX_VOLUME;
    }

    /** C {@code maxVolume}: the loudest a sound may play, in percent. */
    public void setMaxVolume(int maxVolume) {
        this.maxVolume = maxVolume;
    }

    public int maxVolume() { return maxVolume; }

    /**
     * Set the loop hold time from the game's frame rate and the interval at which snapshots really arrive.
     * <p>
     * BEHAVIOR: hold = max({@link #LOOP_HOLD_FRAMES} frames of {@code fps}, {@link #LOOP_HOLD_ARRIVALS} x
     * {@code arrivalSeconds}); a non-positive fps keeps the previous value.
     */
    public void setHold(double fps, double arrivalSeconds) {
        if (fps > 0) {
            holdNanos = (long) (Math.max(LOOP_HOLD_FRAMES / fps, LOOP_HOLD_ARRIVALS * arrivalSeconds) * 1e9);
        }
    }

    /** BEHAVIOR: muting stops every voice and plays nothing until unmuted; unmuting plays only new events. */
    public void setMuted(boolean m) {
        muted = m;
        if (m) {
            for (int v = 0; v < voiceClip.length; v++) {
                dev.stop(v);
                looping[v] = false;
            }
        }
    }

    /** Toggle {@link #setMuted}; returns true when now muted. */
    public boolean toggleMuted() {
        setMuted(!muted);
        return muted;
    }

    public boolean muted() { return muted; }

    /**
     * Play the sounds that arrived since the last call.
     * <p>
     * BEHAVIOR: plays every sound from the last one handled (or the oldest still held) up to
     * {@link SoundQueue#total()}, in order, then remembers the total; muted, they are skipped for good.
     */
    public void drain(SoundQueue q, long nowNanos) {
        long from = Math.max(cursor, q.oldest());
        for (long s = from; s < q.total(); s++) {
            play(q.event(s), q.volume(s), nowNanos);
        }
        cursor = q.total();
    }

    /**
     * Play one sound event (C {@code Handle_audio}).
     * <p>
     * BEHAVIOR: nothing when muted, for unknown or silent events, or at gain 0.
     * BEHAVIOR: a loop clip starts looping on its own voice, or, already looping, takes the new gain; either
     * way it is kept until {@code now + hold}.
     * BEHAVIOR: a one-shot whose clip already started at this same {@code now} on a playing voice only raises
     * that voice's gain to the louder one; otherwise it starts on a free one-shot voice, else on the quietest
     * one (the oldest among equals).
     */
    public void play(int event, int volume, long nowNanos) {
        if (muted) {
            return;
        }
        int clip = map.clip(event);
        if (clip < 0) {
            return;
        }
        float g = gain(volume, maxVolume);
        if (g <= 0) {
            return;
        }
        int lv = loopVoice[clip];
        if (lv >= 0) {
            if (!looping[lv]) {
                dev.play(lv, handle[clip], g, true);
                looping[lv] = true;
            } else if (gain[lv] != g) {
                dev.setGain(lv, g);
            }
            gain[lv] = g;
            loopUntil[lv] = nowNanos + holdNanos;
            return;
        }
        for (int v = firstShot; v < voiceClip.length; v++) {
            if (voiceClip[v] == clip && started[v] == nowNanos && dev.playing(v)) {
                if (g > gain[v]) {
                    gain[v] = g;
                    dev.setGain(v, g);
                }
                return;
            }
        }
        int v = pickVoice();
        dev.play(v, handle[clip], g, false);
        voiceClip[v] = clip;
        started[v] = nowNanos;
        gain[v] = g;
    }

    /**
     * The C client's {@code XBell} (the last 10 seconds of game time, {@code Handle_time_left}): JXPilot has no
     * system bell, so it plays the {@link #BELL_CLIP} clip as a one-shot.
     * <p>
     * BEHAVIOR: nothing when muted or when the sound map has no such clip; otherwise the clip on a one-shot voice
     * (as {@link #play} picks one) at full volume capped by {@code maxVolume}; returns whether it played.
     */
    public boolean bell(long nowNanos) {
        if (muted || bellClip < 0) {
            return false;
        }
        float g = gain(SoundEvent.MAX_VOLUME, maxVolume);
        if (g <= 0) {
            return false;
        }
        int v = pickVoice();
        dev.play(v, handle[bellClip], g, false);
        voiceClip[v] = bellClip;
        started[v] = nowNanos;
        gain[v] = g;
        return true;
    }

    /** A free one-shot voice, else the quietest, oldest first. */
    private int pickVoice() {
        int best = firstShot;
        for (int v = firstShot; v < voiceClip.length; v++) {
            if (!dev.playing(v)) {
                return v;
            }
            if (gain[v] < gain[best] || gain[v] == gain[best] && started[v] - started[best] < 0) {
                best = v;
            }
        }
        return best;
    }

    /**
     * The client's per-frame step: {@link #setHold}(fps, arrivalSeconds), {@link #drain}(q), {@link #update}.
     * <p>
     * BEHAVIOR: plays what arrived, then stops loops whose hold time has passed.
     */
    public void frame(SoundQueue q, double fps, double arrivalSeconds, long nowNanos) {
        setHold(fps, arrivalSeconds);
        drain(q, nowNanos);
        update(nowNanos);
    }

    /**
     * Per frame.
     * <p>
     * BEHAVIOR: a loop whose hold time has passed ({@code now} later than its last event + hold) stops.
     */
    public void update(long nowNanos) {
        for (int v = 0; v < firstShot; v++) {
            if (looping[v] && nowNanos - loopUntil[v] > 0) {
                dev.stop(v);
                looping[v] = false;
            }
        }
    }

    /** Release the device. */
    public void close() {
        dev.close();
    }
}
