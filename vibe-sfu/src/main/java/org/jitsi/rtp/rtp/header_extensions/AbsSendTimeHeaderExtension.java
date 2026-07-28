/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.rtp.rtp.header_extensions;

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.util.FieldParsers;

import java.time.Instant;

/**
 * https://webrtc.org/experiments/rtp-hdrext/abs-send-time/
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | ID   |  LEN   |         AbsSendTime value                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public final class AbsSendTimeHeaderExtension
{
    private AbsSendTimeHeaderExtension()
    {
    }

    public static final int DATA_SIZE_BYTES = 3;

    /**
     * One billion.
     */
    private static final long B = 1_000_000_000;

    public static void setTime(RtpPacket.HeaderExtension ext, long timestampNanos)
    {
        setTime(ext.getBuffer(), ext.getDataOffset(), timestampNanos);
    }

    private static void setTime(byte[] buf, int offset, long timestampNanos)
    {
        long fraction = ((timestampNanos % B) * (1 << 18) / B);
        long seconds = ((timestampNanos / B) % 64); // 6 bits only

        long timestamp = ((seconds << 18) | fraction) & 0x00FFFFFFL;

        ByteArrayExtensions.put3Bytes(buf, offset, (int) timestamp);
    }

    /**
     * Gets the timestamp converted to nanoseconds.
     */
    public static Instant getTime(RtpPacket.HeaderExtension ext)
    {
        return getTime(ext.getBuffer(), ext.getDataOffset());
    }

    private static Instant getTime(byte[] buf, int dataOffset)
    {
        int seconds = FieldParsers.getBitsAsInt(buf, dataOffset, 0, 6);
        double fraction = (double) (
            FieldParsers.getBitsAsInt(buf, dataOffset, 6, 2) +
                FieldParsers.getShortAsInt(buf, dataOffset + 1)
        ) / 0x03ffff;

        Instant instantMillis = Instant.ofEpochSecond(seconds);
        return instantMillis.plusNanos((long) (fraction * B));
    }
}
