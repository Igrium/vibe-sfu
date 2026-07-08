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

package org.jitsi.rtp.rtp;

import org.jitsi.rtp.Packet;
import org.jitsi.rtp.util.BufferPool;
import org.jitsi.rtp.util.RtpUtils;

import java.util.List;

public class RedPacketBuilder<PacketType extends RtpPacket>
{
    private final RedPacketParser.PacketFactory<PacketType> createPacket;

    public RedPacketBuilder(RedPacketParser.PacketFactory<PacketType> createPacket)
    {
        this.createPacket = createPacket;
    }

    /**
     * Builds and returns a RED packet that contains {@code primary} as the primary encodings, and {@code redundancy}
     * as secondary encodings.
     * Note that this assumes the primary and secondary packets can be encoded in RED and throws as
     * {@link IllegalArgumentException} otherwise (notably when the timestamp difference between the primary and any
     * of the redundancy packets is too large).
     */
    public PacketType build(int redPayloadType, RtpPacket primary, List<RtpPacket> redundancy)
    {
        int redundancyPayloadLengthSum = 0;
        for (RtpPacket p : redundancy)
        {
            redundancyPayloadLengthSum += p.getPayloadLength();
        }
        int bytesNeeded = primary.length + 1 + redundancyPayloadLengthSum + redundancy.size() * 4;

        byte[] buf = BufferPool.getArray(
            bytesNeeded + RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET + Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
        );

        int currentOffset = RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET;
        int primaryHeaderLength = primary.getHeaderLength();

        System.arraycopy(
            primary.buffer,
            primary.offset,
            buf,
            currentOffset,
            primaryHeaderLength
        );
        currentOffset += primaryHeaderLength;

        int redHeaderOffset = currentOffset;

        // Skip the RED headers and point to the start of RED payloads
        currentOffset += 1 + 4 * redundancy.size();

        for (RtpPacket it : redundancy)
        {
            int payloadLength = it.getPayloadLength();

            RedundancyBlockHeader header = new RedundancyBlockHeader(
                (byte) it.getPayloadType(),
                RtpUtils.getTimestampDiffAsInt(primary.getTimestamp(), it.getTimestamp()),
                payloadLength
            );
            redHeaderOffset += header.write(buf, redHeaderOffset);

            System.arraycopy(
                it.buffer,
                it.getPayloadOffset(),
                buf,
                currentOffset,
                payloadLength
            );
            currentOffset += payloadLength;
        }

        PrimaryBlockHeader primaryHeader = new PrimaryBlockHeader((byte) primary.getPayloadType());
        redHeaderOffset += primaryHeader.write(buf, redHeaderOffset);

        System.arraycopy(
            primary.buffer,
            primary.getPayloadOffset(),
            buf,
            currentOffset,
            primary.getPayloadLength()
        );

        PacketType result = createPacket.create(buf, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, bytesNeeded);
        result.setPayloadType(redPayloadType);
        return result;
    }
}
