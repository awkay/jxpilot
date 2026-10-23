package com.fulcrologic.jxpilot.common.net;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Transfer of the server's effective configuration (all options + mapData, as config-file text
 * produced by {@link Options#toConfigText()}) to a client over the reliable channel:
 * WELCOME announces length/compressed length/CRC32, then DATA_CHUNK messages carry the deflated
 * bytes. The client parses the text with the same {@link ConfigParser}. Not a hot path.
 * <p>
 * Encoding is expensive (about 9 ms for planetx.xp): call {@link #encode} once per map load (and again
 * only if options change), keep the immutable {@link Blob}, and give every connecting client its own
 * {@link Sender} over that shared blob. Never encode per client, and never on the simulation thread.
 */
public final class ConfigTransfer {
    private ConfigTransfer() {}

    /** Deflated config text with its metadata. Immutable; share one instance among all senders. */
    public static final class Blob {
        public final byte[] compressed;
        public final int length;
        public final int crc32;

        Blob(byte[] compressed, int length, int crc32) {
            this.compressed = compressed;
            this.length = length;
            this.crc32 = crc32;
        }

        /** Copy the metadata into a WELCOME message. */
        public void fill(Msg.Welcome w) {
            w.configLength = length;
            w.configCompressedLength = compressed.length;
            w.configCrc32 = crc32;
        }
    }

    /** Encode config text (Latin-1). */
    public static Blob encode(String configText) {
        byte[] raw = configText.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(raw);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        while (!d.finished()) {
            int n = d.deflate(tmp);
            bos.write(tmp, 0, n);
        }
        d.end();
        return new Blob(bos.toByteArray(), raw.length, (int) crc.getValue());
    }

    /** Server side: queues DATA_CHUNK messages as the reliable window allows. */
    public static final class Sender {
        private final Blob blob;
        private int offset;

        public Sender(Blob blob) {
            this.blob = blob;
        }

        /**
         * Queue as many chunks as fit while leaving {@code reserve} free slots for other messages.
         * <p>
         * BEHAVIOR: queues 1 KB chunks in order while more than {@code reserve} slots are free.
         *
         * @return true when everything has been queued
         */
        public boolean pump(ReliableChannel ch, int reserve) {
            while (offset < blob.compressed.length && ch.freeSlots() > reserve) {
                int len = Math.min(Protocol.DATA_CHUNK_SIZE, blob.compressed.length - offset);
                Msg.DataChunk.send(ch, blob.compressed, offset, len);
                offset += len;
            }
            return offset >= blob.compressed.length;
        }

        public boolean done() {
            return offset >= blob.compressed.length;
        }
    }

    /** Client side: collects chunks after WELCOME and decodes the text. */
    public static final class Receiver {
        private final byte[] data;
        private final int length;
        private final int crc;
        private int received;

        public Receiver(Msg.Welcome w) {
            this.data = new byte[w.configCompressedLength];
            this.length = w.configLength;
            this.crc = w.configCrc32;
        }

        /**
         * Handle a DATA_CHUNK payload (positioned after the type byte).
         * <p>
         * BEHAVIOR: copies the chunk to its offset; returns true once all announced bytes arrived.
         * BEHAVIOR: throws for a chunk outside the announced size.
         *
         * @return true when all bytes have arrived
         */
        public boolean onChunk(ByteBuffer payload) {
            int start = payload.remaining() - 4;
            int endOff = Msg.DataChunk.read(payload, data);
            if (endOff < 0) {
                throw new IllegalStateException("DATA_CHUNK out of range");
            }
            received += start;
            return complete();
        }

        public boolean complete() {
            return received >= data.length;
        }

        /** BEHAVIOR: inflates and returns the text; throws on corrupt data, wrong length or CRC mismatch. */
        public String text() {
            Inflater inf = new Inflater();
            inf.setInput(data);
            byte[] raw = new byte[length];
            try {
                // The output buffer has the announced size, so one call inflates everything that is there.
                int n = inf.inflate(raw);
                if (n != length || !inf.finished()) {
                    throw new IllegalStateException("config length mismatch");
                }
            } catch (DataFormatException e) {
                throw new IllegalStateException("corrupt config data", e);
            } finally {
                inf.end();
            }
            CRC32 c = new CRC32();
            c.update(raw);
            if ((int) c.getValue() != crc) {
                throw new IllegalStateException("config CRC mismatch");
            }
            return new String(raw, StandardCharsets.ISO_8859_1);
        }

        /** Decode and parse into a fresh {@link Options} (as map origin). */
        public Options options() {
            Options o = new Options();
            ConfigParser.parse(text(), "server", OptionOrigin.MAP, o);
            return o;
        }
    }
}
