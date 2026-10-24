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

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;

import java.nio.IntBuffer;
import java.util.Arrays;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.openal.ALC10.*;

/**
 * {@link AudioDevice} over LWJGL OpenAL (OpenAL Soft): one context on the device, one non-positional
 * source per voice, one buffer per clip. Thin glue; {@link SoundPlayer} holds the logic. Per-event calls are
 * plain JNI calls and allocate nothing.
 */
public final class OpenAlDevice implements AudioDevice {
    private final long device;
    private final long context;
    private final int[] sources;
    private int[] buffers = new int[0];

    private OpenAlDevice(long device, long context, int[] sources) {
        this.device = device;
        this.context = context;
        this.sources = sources;
    }

    /**
     * Open an output device with {@code voices} sources.
     * <p>
     * BEHAVIOR: returns null, and leaves nothing open, when the device (null = the default) cannot be opened,
     * no context can be made or the sources cannot be created; the reason goes to {@code why[0]} when given.
     * Errors loading the OpenAL library itself propagate (see {@link SoundSystem}).
     */
    public static OpenAlDevice open(String deviceName, int voices, String[] why) {
        long dev = alcOpenDevice(deviceName);
        if (dev == 0) {
            return fail(why, "no audio device" + (deviceName != null ? " '" + deviceName + "'" : ""));
        }
        ALCCapabilities caps = ALC.createCapabilities(dev);
        long ctx = alcCreateContext(dev, (IntBuffer) null);
        if (ctx == 0 || !alcMakeContextCurrent(ctx)) {
            if (ctx != 0) {
                alcDestroyContext(ctx);
            }
            alcCloseDevice(dev);
            return fail(why, "cannot create an OpenAL context");
        }
        AL.createCapabilities(caps);
        alGetError();
        int[] src = new int[voices];
        alGenSources(src);
        if (alGetError() != AL_NO_ERROR) {
            alcMakeContextCurrent(0);
            alcDestroyContext(ctx);
            alcCloseDevice(dev);
            return fail(why, "cannot create " + voices + " OpenAL sources");
        }
        for (int s : src) {
            alSourcei(s, AL_SOURCE_RELATIVE, AL_TRUE);
            alSource3f(s, AL_POSITION, 0, 0, 0);
        }
        return new OpenAlDevice(dev, ctx, src);
    }

    private static OpenAlDevice fail(String[] why, String reason) {
        if (why != null && why.length > 0) {
            why[0] = reason;
        }
        return null;
    }

    @Override
    public int voices() {
        return sources.length;
    }

    /** The PCM must be in a direct buffer; OpenAL copies it. WAV's little-endian samples are native order on every bundled platform. */
    @Override
    public int load(Wav wav) {
        int b = alGenBuffers();
        alBufferData(b, wav.channels() == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, wav.pcm(), wav.sampleRate());
        buffers = Arrays.copyOf(buffers, buffers.length + 1);
        buffers[buffers.length - 1] = b;
        return b;
    }

    @Override
    public void play(int voice, int clip, float gain, boolean loop) {
        int s = sources[voice];
        alSourceStop(s);
        alSourcei(s, AL_BUFFER, clip);
        alSourcei(s, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
        alSourcef(s, AL_GAIN, gain);
        alSourcePlay(s);
    }

    @Override
    public void setGain(int voice, float gain) {
        alSourcef(sources[voice], AL_GAIN, gain);
    }

    @Override
    public void stop(int voice) {
        alSourceStop(sources[voice]);
    }

    @Override
    public boolean playing(int voice) {
        return alGetSourcei(sources[voice], AL_SOURCE_STATE) == AL_PLAYING;
    }

    @Override
    public void close() {
        alSourceStopv(sources);
        alDeleteSources(sources);
        alDeleteBuffers(buffers);
        alcMakeContextCurrent(0);
        alcDestroyContext(context);
        alcCloseDevice(device);
    }
}
