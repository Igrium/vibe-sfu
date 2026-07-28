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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses RED (RFC2198) packets.
 */
public class RedPacketParser<PacketType extends RtpPacket>
{
    /**
     * A function to create packets from redundancy blocks (used so we can create a packet with the correct class).
     */
    private final PacketFactory<PacketType> createPacket;

    /**
     * A function to create packets from redundancy blocks (used so we can create a packet with the correct class).
     */
    @FunctionalInterface
    public interface PacketFactory<PacketType extends RtpPacket>
    {
        PacketType create(byte[] buffer, int offset, int length);
    }

    public RedPacketParser(PacketFactory<PacketType> createPacket)
    {
        this.createPacket = createPacket;
    }

    /**
     * Destructively parses a specific {@link RtpPacket} as RED. The redundant RED blocks are removed from the
     * payload, leaving only the primary block as payload, and the payload type is changed to the payload type of
     * the primary block. The transformed packet is no longer a RED packet, so the operation must not be performed
     * again.
     *
     * If {@code parseRedundancy} is set, the redundancy blocks are parsed as {@code PacketType} into newly
     * allocated buffers.
     *
     * @param rtpPacket the packet to parse
     * @param parseRedundancy whether to parse redundancy packets
     * @return The list of parsed redundancy packets.
     */
    public List<PacketType> decapsulate(RtpPacket rtpPacket, boolean parseRedundancy)
    {
        int currentOffset = rtpPacket.getPayloadOffset();

        List<RedundancyBlockHeader> redundancyBlockHeaders = new ArrayList<>();
        PrimaryBlockHeader primaryBlockHeader = null;
        BlockHeader blockHeader;
        do
        {
            if (currentOffset >= rtpPacket.offset + rtpPacket.length)
            {
                throw new IllegalArgumentException(
                    "Invalid RED packet: no last header block found within the allowed length."
                );
            }
            blockHeader = BlockHeader.parse(rtpPacket.buffer, currentOffset);
            if (blockHeader instanceof PrimaryBlockHeader)
            {
                primaryBlockHeader = (PrimaryBlockHeader) blockHeader;
            }
            else if (blockHeader instanceof RedundancyBlockHeader)
            {
                redundancyBlockHeaders.add((RedundancyBlockHeader) blockHeader);
            }
            currentOffset += blockHeader.getHeaderLength();
        }
        while (blockHeader instanceof RedundancyBlockHeader);

        List<PacketType> packets = parseRedundancy ? new ArrayList<>() : null;
        for (int index = 0; index < redundancyBlockHeaders.size(); index++)
        {
            RedundancyBlockHeader it = redundancyBlockHeaders.get(index);
            int blockLength = it.getLength();
            if (currentOffset + blockLength > rtpPacket.offset + rtpPacket.length)
            {
                throw new IllegalArgumentException("Invalid RED packet: blocks extend past the packet length.");
            }

            if (parseRedundancy)
            {
                byte[] byteArray = BufferPool.getArray(
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET +
                        RtpHeader.FIXED_HEADER_SIZE_BYTES + blockLength +
                        Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
                );

                System.arraycopy(
                    rtpPacket.buffer,
                    rtpPacket.offset,
                    byteArray,
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET,
                    RtpHeader.FIXED_HEADER_SIZE_BYTES
                );
                RtpHeader.setCsrcCount(byteArray, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, 0);
                RtpHeader.setHasExtensions(byteArray, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, false);
                System.arraycopy(
                    rtpPacket.buffer,
                    currentOffset,
                    byteArray,
                    RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET + RtpHeader.FIXED_HEADER_SIZE_BYTES,
                    blockLength
                );

                PacketType redundancyPacket = createPacket.create(
                    byteArray, RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET, RtpHeader.FIXED_HEADER_SIZE_BYTES + blockLength
                );
                redundancyPacket.setPayloadType(it.getPt());
                redundancyPacket.setTimestamp(redundancyPacket.getTimestamp() - it.getTimestampOffset());
                // RFC2198 describes how to reconstruct the timestamp and payload type, but not an RTP
                // sequence number. However, we operate on the RTP level and need to reconstruct an RTP
                // packet. So we make the assumption that the redundant blocks represent the packets
                // directly proceeding the primary.
                redundancyPacket.setSequenceNumber(
                    RtpSequenceNumber.toRtpSequenceNumber(
                        redundancyPacket.getSequenceNumber() - (redundancyBlockHeaders.size() - index)
                    ).getValue()
                );

                if (packets != null)
                {
                    packets.add(redundancyPacket);
                }
            }
            currentOffset += blockLength;
        }

        // We've now read past the redundancy blocks, and currentOffset points to the payload of the primary block.
        // Remove RED "in-place" by shifting the header.
        int headerLength = rtpPacket.getHeaderLength();
        int newOffset = currentOffset - headerLength;
        int newLength = rtpPacket.length - currentOffset + rtpPacket.offset + headerLength;
        System.arraycopy(
            rtpPacket.buffer,
            rtpPacket.offset,
            rtpPacket.buffer,
            newOffset,
            headerLength
        );
        rtpPacket.offset = newOffset;
        rtpPacket.length = newLength;
        rtpPacket.setPayloadType(primaryBlockHeader.getPt());

        return packets != null ? packets : Collections.emptyList();
    }
}
