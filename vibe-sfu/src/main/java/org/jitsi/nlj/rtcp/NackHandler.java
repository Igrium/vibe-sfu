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
package org.jitsi.nlj.rtcp;

import org.jitsi.nlj.PacketHandler;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.PacketOrigin;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.NodeStatsProducer;
import org.jitsi.nlj.util.ArrayCache;
import org.jitsi.nlj.util.BufferPool;
import org.jitsi.nlj.util.PacketCache;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging2.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * When a nack packet is received, the {@link NackHandler} will try to retrieve the
 * nacked packets from the cache and then send them to the RTX output pipeline.
 *
 * TODO(port): upstream also implements {@code EndpointConnectionStats.EndpointConnectionStatsListener}
 * (dropped since {@code stats/EndpointConnectionStats} is not yet ported); {@link #onRttUpdate} is kept as a plain
 * method so it can still be wired up manually once that type exists.
 */
public class NackHandler implements NodeStatsProducer, RtcpListener
{
    private int numNacksReceived = 0;
    private int numNackedPackets = 0;
    private int numRetransmittedPackets = 0;
    private int numPacketsNotResentDueToDelay = 0;
    private int numCacheMisses = 0;
    private final PacketCache packetCache;
    private final PacketHandler onNackedPacketsReady;
    private final Logger logger;
    private double currRtt = -1.0;

    public NackHandler(PacketCache packetCache, PacketHandler onNackedPacketsReady, Logger parentLogger)
    {
        this.packetCache = packetCache;
        this.onNackedPacketsReady = onNackedPacketsReady;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    @Override
    public void rtcpPacketReceived(RtcpPacket packet, Instant receivedTime)
    {
        if (packet instanceof RtcpFbNackPacket)
        {
            onNackPacket((RtcpFbNackPacket) packet);
        }
    }

    private void onNackPacket(RtcpFbNackPacket nackPacket)
    {
        long ssrc = nackPacket.getMediaSourceSsrc();
        logger.debug(() -> "Nack received for " + ssrc + " " + nackPacket.getMissingSeqNums());
        long now = System.currentTimeMillis();

        numNacksReceived++;
        List<RtpPacket> nackedPackets = new ArrayList<>();
        numNackedPackets += nackPacket.getMissingSeqNums().size();
        for (int missingSeqNum : nackPacket.getMissingSeqNums())
        {
            ArrayCache.Container<RtpPacket> container = packetCache.get(ssrc, missingSeqNum);
            if (container != null)
            {
                long delay = now - container.timeAdded;
                boolean shouldResendPacket = (currRtt == -1.0) || (delay >= Math.min(currRtt * .9, currRtt - 5));
                if (shouldResendPacket)
                {
                    // The cache returns a null container on failure, never a container with a null item.
                    nackedPackets.add(container.item);
                    packetCache.updateTimestamp(ssrc, missingSeqNum, now);
                    numRetransmittedPackets++;
                }
                else
                {
                    BufferPool.returnBuffer(container.item.buffer);
                    numPacketsNotResentDueToDelay++;
                }
            }
            else
            {
                int finalMissingSeqNum = missingSeqNum;
                logger.debug(() -> "Nack'd packet " + ssrc + " " + finalMissingSeqNum + " wasn't in cache, unable to retransmit");
                numCacheMisses++;
            }
        }
        for (RtpPacket packet : nackedPackets)
        {
            PacketInfo packetInfo = new PacketInfo(packet);
            packetInfo.setPacketOrigin(PacketOrigin.Retransmission);
            onNackedPacketsReady.processPacket(packetInfo);
        }
    }

    public void onRttUpdate(double newRttMs)
    {
        currRtt = newRttMs;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock stats = new NodeStatsBlock("Nack handler");
        stats.addNumber("num_nack_packets_received", numNackedPackets);
        stats.addNumber("num_nacked_packets", numNackedPackets);
        stats.addNumber("num_retransmitted_packets", numRetransmittedPackets);
        stats.addNumber("num_packets_not_retransmitted", numPacketsNotResentDueToDelay);
        stats.addNumber("num_cache_misses", numCacheMisses);
        return stats;
    }
}
