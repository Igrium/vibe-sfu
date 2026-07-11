/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.transform.node.incoming;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.TransformerNode;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging2.Logger;

/**
 * A node which removes padding from RTP packets.
 * Padding-only packets are marked as shouldDiscard in their PacketInfo.
 */
public class PaddingTermination extends TransformerNode
{
    private final Logger logger;
    private int numPaddedPacketsSeen = 0;
    private int numPaddingOnlyPacketsSeen = 0;

    public PaddingTermination(Logger parentLogger)
    {
        super("Padding termination");
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();

        if (rtpPacket.getHasPadding())
        {
            removePadding(rtpPacket);
            packetInfo.resetPayloadVerification();
            numPaddedPacketsSeen++;
            if (rtpPacket.getPayloadLength() == 0)
            {
                numPaddingOnlyPacketsSeen++;
                packetInfo.setShouldDiscard(true);
            }
        }

        return packetInfo;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("num_padded_packets_seen", numPaddedPacketsSeen);
        block.addNumber("num_padding_only_packets_seen", numPaddingOnlyPacketsSeen);
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_padded_packets_seen", numPaddedPacketsSeen);
        json.put("num_padding_only_packets_seen", numPaddingOnlyPacketsSeen);
        return json;
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    /**
     * (Deviation: upstream's {@code RtpPacket.removePadding} is a Kotlin extension function on
     * {@code org.jitsi.rtp.rtp.RtpPacket}; Java has no extension functions, so it becomes a private static
     * helper here, the only place it is used.)
     */
    private static void removePadding(RtpPacket rtpPacket)
    {
        int paddingSize = rtpPacket.getPaddingSize();
        rtpPacket.setLength(Math.max(rtpPacket.getLength() - paddingSize, rtpPacket.getHeaderLength()));
        rtpPacket.setHasPadding(false);
    }
}
