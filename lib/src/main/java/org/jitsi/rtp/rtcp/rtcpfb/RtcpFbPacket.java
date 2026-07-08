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

package org.jitsi.rtp.rtcp.rtcpfb;

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.rtcp.RtcpHeader;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.PayloadSpecificRtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket;
import org.jitsi.rtp.util.FieldParsers;

import java.util.List;

/**
 * https://tools.ietf.org/html/rfc4585#section-6.1
 *     0                   1                   2                   3
 *     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |V=2|P|   FMT   |       PT      |          length               |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of packet sender                        |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of media source                         |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    :            Feedback Control Information (FCI)                 :
 *    :                                                               :
 *
 *    Note that an RTCP FB packet re-interprets the standard report count
 *    (RC) field of the RTCP header as a FMT field
 */
public abstract class RtcpFbPacket extends RtcpPacket
{
    public static final List<Integer> PACKET_TYPES =
        List.of(TransportLayerRtcpFbPacket.PT, PayloadSpecificRtcpFbPacket.PT);
    public static final int MEDIA_SOURCE_SSRC_OFFSET = RtcpHeader.SIZE_BYTES;
    public static final int HEADER_SIZE = RtcpHeader.SIZE_BYTES + 4;
    public static final int FCI_OFFSET = HEADER_SIZE;

    public RtcpFbPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    public long getMediaSourceSsrc()
    {
        return getMediaSourceSsrc(buffer, offset);
    }

    public void setMediaSourceSsrc(long value)
    {
        setMediaSourceSsrc(buffer, offset, value);
    }

    public static int getFmt(byte[] buf, int baseOffset)
    {
        return RtcpHeader.getReportCount(buf, baseOffset);
    }

    public static long getMediaSourceSsrc(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + MEDIA_SOURCE_SSRC_OFFSET);
    }

    public static void setMediaSourceSsrc(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + MEDIA_SOURCE_SSRC_OFFSET, (int) value);
    }

    public static RtcpFbPacket parse(byte[] buf, int offset, int length)
    {
        int packetType = RtcpHeader.getPacketType(buf, offset);
        int fmt = getFmt(buf, offset);
        if (packetType == TransportLayerRtcpFbPacket.PT)
        {
            if (fmt == RtcpFbNackPacket.FMT)
            {
                return new RtcpFbNackPacket(buf, offset, length);
            }
            else if (fmt == RtcpFbTccPacket.FMT)
            {
                return new RtcpFbTccPacket(buf, offset, length);
            }
            else if (fmt == RtcpFbCcfbPacket.FMT)
            {
                return new RtcpFbCcfbPacket(buf, offset, length);
            }
            else
            {
                return new UnsupportedRtcpFbPacket(buf, offset, length);
            }
        }
        else if (packetType == PayloadSpecificRtcpFbPacket.PT)
        {
            if (fmt == RtcpFbFirPacket.FMT)
            {
                return new RtcpFbFirPacket(buf, offset, length);
            }
            else if (fmt == RtcpFbPliPacket.FMT)
            {
                return new RtcpFbPliPacket(buf, offset, length);
            }
            else if (fmt == RtcpFbRembPacket.FMT)
            {
                return new RtcpFbRembPacket(buf, offset, length);
            }
            else
            {
                return new UnsupportedRtcpFbPacket(buf, offset, length);
            }
        }
        else
        {
            throw new RuntimeException("Unrecognized RTCPFB payload type: " + Integer.toHexString(packetType));
        }
    }
}
