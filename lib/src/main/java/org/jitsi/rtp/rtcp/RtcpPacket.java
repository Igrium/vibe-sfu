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

package org.jitsi.rtp.rtcp;

import org.jitsi.rtp.Packet;
import org.jitsi.rtp.extensions.PacketExtensions;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;

/**
 * Models the RTCP header as defined in https://tools.ietf.org/html/rfc3550#section-6.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|    RC   |   PT=SR=200   |             length            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         SSRC of sender                        |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *
 * length: 16 bits
 *   The length of this RTCP packet in 32-bit words minus one,
 *   including the header and any padding.  (The offset of one makes
 *   zero a valid length and avoids a possible infinite loop in
 *   scanning a compound RTCP packet, while counting 32-bit words
 *   avoids a validity check for a multiple of 4.)
 */
public abstract class RtcpPacket extends Packet
{
    public RtcpPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    public int getVersion()
    {
        return RtcpHeader.getVersion(buffer, offset);
    }

    public void setVersion(int value)
    {
        RtcpHeader.setVersion(buffer, offset, value);
    }

    public boolean getHasPadding()
    {
        return RtcpHeader.hasPadding(buffer, offset);
    }

    public int getReportCount()
    {
        return RtcpHeader.getReportCount(buffer, offset);
    }

    public int getPacketType()
    {
        return RtcpHeader.getPacketType(buffer, offset);
    }

    public int getLengthField()
    {
        return RtcpHeader.getLength(buffer, offset);
    }

    public long getSenderSsrc()
    {
        return RtcpHeader.getSenderSsrc(buffer, offset);
    }

    public void setSenderSsrc(long value)
    {
        RtcpHeader.setSenderSsrc(buffer, offset, value);
    }

    public int getPacketLength()
    {
        return (getLengthField() + 1) * 4;
    }

    /**
     * Effectively disable the payload verification for RTCP packets, since in practice we change them very often
     */
    @Override
    public String getPayloadVerification()
    {
        return "rtcp";
    }

    @Override
    public abstract RtcpPacket clone();

    public static RtcpPacket parse(byte[] buf, int offset, int bytesRemaining) throws InvalidRtcpException
    {
        int packetType = RtcpHeader.getPacketType(buf, offset);
        int packetLengthBytes = (RtcpHeader.getLength(buf, offset) + 1) * 4;
        if (packetLengthBytes > bytesRemaining)
        {
            throw new InvalidRtcpException(buf, offset, "length " + packetLengthBytes + " > available " + bytesRemaining);
        }
        if (packetType == RtcpByePacket.PT)
        {
            return new RtcpByePacket(buf, offset, packetLengthBytes);
        }
        else if (packetType == RtcpRrPacket.PT)
        {
            return new RtcpRrPacket(buf, offset, packetLengthBytes);
        }
        else if (packetType == RtcpSrPacket.PT)
        {
            return new RtcpSrPacket(buf, offset, packetLengthBytes);
        }
        else if (packetType == RtcpSdesPacket.PT)
        {
            return new RtcpSdesPacket(buf, offset, packetLengthBytes);
        }
        else if (RtcpFbPacket.PACKET_TYPES.contains(packetType))
        {
            return RtcpFbPacket.parse(buf, offset, packetLengthBytes);
        }
        else if (packetType == RtcpXrPacket.PT)
        {
            return new RtcpXrPacket(buf, offset, packetLengthBytes);
        }
        else if (packetType >= PacketExtensions.RTCP_PACKET_TYPE_RANGE_START &&
            packetType <= PacketExtensions.RTCP_PACKET_TYPE_RANGE_END)
        {
            return new UnsupportedRtcpPacket(buf, offset, packetLengthBytes);
        }
        else
        {
            throw new InvalidRtcpException(buf, offset, "type " + packetType);
        }
    }
}
