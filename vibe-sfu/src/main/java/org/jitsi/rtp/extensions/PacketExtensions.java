/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.rtp.extensions;

import org.jitsi.rtp.Packet;
import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.rtcp.RtcpHeader;
import org.jitsi.rtp.rtp.RtpHeader;

/**
 * "The process for demultiplexing a packet is as follows.  The receiver
 * looks at the first byte of the packet."
 *
 * +----------------+
 * |        [0..3] -+--&gt; forward to STUN
 * |                |
 * |      [16..19] -+--&gt; forward to ZRTP
 * |                |
 * |      [20..63] -+--&gt; forward to DTLS
 * |                |
 * |      [64..79] -+--&gt; forward to TURN Channel
 * |                |
 * |    [128..191] -+--&gt; forward to RTP/RTCP
 * +----------------+
 *
 * See [https://tools.ietf.org/html/rfc7983#section-7]
 *
 *
 * RTP/RTCP are further demultiplexed based on the packet type (second byte)
 */
public final class PacketExtensions
{
    private PacketExtensions()
    {
    }

    private static final int DTLS_RANGE_START = 20;
    private static final int DTLS_RANGE_END = 63;
    private static final int RTP_RTCP_RANGE_START = 128;
    private static final int RTP_RTCP_RANGE_END = 191;
    public static final int RTCP_PACKET_TYPE_RANGE_START = 192;
    public static final int RTCP_PACKET_TYPE_RANGE_END = 223;

    public static boolean looksLikeRtp(Packet packet)
    {
        if (packet.getLength() < RtpHeader.FIXED_HEADER_SIZE_BYTES)
        {
            return false;
        }

        int b0 = Unsigned.toPositiveInt(packet.getBuffer()[packet.getOffset()]);
        int b1 = Unsigned.toPositiveInt(packet.getBuffer()[packet.getOffset() + 1]);
        return b0 >= RTP_RTCP_RANGE_START && b0 <= RTP_RTCP_RANGE_END &&
            !(b1 >= RTCP_PACKET_TYPE_RANGE_START && b1 <= RTCP_PACKET_TYPE_RANGE_END);
    }

    public static boolean looksLikeRtcp(Packet packet)
    {
        if (packet.getLength() < RtcpHeader.SIZE_BYTES)
        {
            return false;
        }
        int b0 = Unsigned.toPositiveInt(packet.getBuffer()[packet.getOffset()]);
        int b1 = Unsigned.toPositiveInt(packet.getBuffer()[packet.getOffset() + 1]);
        return b0 >= RTP_RTCP_RANGE_START && b0 <= RTP_RTCP_RANGE_END &&
            b1 >= RTCP_PACKET_TYPE_RANGE_START && b1 <= RTCP_PACKET_TYPE_RANGE_END;
    }

    public static boolean looksLikeDtls(Packet packet)
    {
        if (packet.getLength() < 1)
        {
            return false;
        }
        int b0 = Unsigned.toPositiveInt(packet.getBuffer()[packet.getOffset()]);
        return b0 >= DTLS_RANGE_START && b0 <= DTLS_RANGE_END;
    }
}
