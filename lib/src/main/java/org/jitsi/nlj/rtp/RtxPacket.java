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

package org.jitsi.nlj.rtp;

import org.jitsi.nlj.util.RtpPacketExtensions;
import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.util.FieldParsers;

/**
 * https://tools.ietf.org/html/rfc4588#section-4
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         RTP Header                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            OSN                |                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
 * |                  Original RTP Packet Payload                  |
 * |                                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class RtxPacket
{
    public static int getOriginalSequenceNumber(RtpPacket rtxPacket)
    {
        return FieldParsers.getShortAsInt(rtxPacket.buffer, rtxPacket.offset + rtxPacket.getHeaderLength());
    }

    /**
     * Removes the original sequence number by shifting the header 2
     * bytes to the right
     */
    public static RtpPacket removeOriginalSequenceNumber(RtpPacket rtxPacket)
    {
        // Remove the original sequence number by moving the RTP header 2 bytes to the right
        // Note this changes the buffer underlying the RtpPacket -- this is safe (but fragile)
        // because we leave header values (which are cached) unchanged.
        System.arraycopy(rtxPacket.buffer, rtxPacket.offset, rtxPacket.buffer, rtxPacket.offset + 2,
            rtxPacket.getHeaderLength());
        rtxPacket.offset += 2;
        rtxPacket.length -= 2;
        return rtxPacket;
    }

    public static RtpPacket addOriginalSequenceNumber(RtpPacket rtpPacket)
    {
        // TODO: possible optimization to try to shift the header left instead
        RtpPacketExtensions.shiftPayloadRight(rtpPacket, 2);
        ByteArrayExtensions.putShort(
            rtpPacket.buffer,
            rtpPacket.offset + rtpPacket.getHeaderLength(),
            (short) rtpPacket.getSequenceNumber()
        );
        return rtpPacket;
    }
}
