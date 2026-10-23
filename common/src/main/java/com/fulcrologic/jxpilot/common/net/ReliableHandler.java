package com.fulcrologic.jxpilot.common.net;

import java.nio.ByteBuffer;

/** Receives in-order reliable messages from {@link ReliableChannel#read}. */
public interface ReliableHandler {
    /**
     * @param type    {@link ReliableType}
     * @param payload positioned after the type byte, limit at the message end; valid only during the call
     */
    void onReliableMessage(int type, ByteBuffer payload);
}
