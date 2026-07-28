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
package org.jitsi.nlj;

import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.NodeStatsProducer;

import static org.jitsi.nlj.util.NodeStatsBlockExtensions.addMbps;

/**
 * A {@link PacketHandler} which tracks statistics about the packets it handles
 */
public abstract class StatsKeepingPacketHandler implements PacketHandler, NodeStatsProducer
{
    private final Statistics statistics = new Statistics();

    @Override
    public final void processPacket(PacketInfo packetInfo)
    {
        long now = System.currentTimeMillis();
        statistics.packetsReceived++;
        statistics.bytesReceived += packetInfo.getPacket().getLength();
        if (statistics.firstPacketReceivedTime == 0L)
        {
            statistics.firstPacketReceivedTime = now;
        }
        statistics.lastPacketReceivedTime = now;

        doProcessPacket(packetInfo);
    }

    /**
     * We override {@link #processPacket(PacketInfo)} above and prevent subclasses from doing so
     * to make sure this class sees all packets that pass through it and it
     * can track stats correctly.  This method should be implemented by
     * subclasses as a stand-in for {@link #processPacket(PacketInfo)}, as it is called once
     * all the stat-tracking has been done.
     */
    protected abstract void doProcessPacket(PacketInfo packetInfo);

    @Override
    public NodeStatsBlock getNodeStats()
    {
        return statistics.getNodeStats();
    }

    /**
     * Statistics common to {@link PacketHandler}s which are interesting to track
     */
    public static class Statistics implements NodeStatsProducer
    {
        private static final String RECEIVED_PACKETS = "received_packets";
        private static final String RECEIVED_BYTES = "received_bytes";
        private static final String RECEIVED_DURATION_MS = "received_duration_ms";
        private static final String RECEIVED_BITRATE_MBPS = "received_bitrate_mbps";

        public long firstPacketReceivedTime = 0;
        public long lastPacketReceivedTime = 0;
        public long bytesReceived = 0;
        public long packetsReceived = 0;

        public Statistics()
        {
        }

        public Statistics(long firstPacketReceivedTime, long lastPacketReceivedTime, long bytesReceived, long packetsReceived)
        {
            this.firstPacketReceivedTime = firstPacketReceivedTime;
            this.lastPacketReceivedTime = lastPacketReceivedTime;
            this.bytesReceived = bytesReceived;
            this.packetsReceived = packetsReceived;
        }

        @Override
        public NodeStatsBlock getNodeStats()
        {
            NodeStatsBlock block = new NodeStatsBlock("Packet handler stats");
            block.addNumber(RECEIVED_PACKETS, packetsReceived);
            block.addNumber(RECEIVED_BYTES, bytesReceived);
            block.addNumber(RECEIVED_DURATION_MS, lastPacketReceivedTime - firstPacketReceivedTime);
            addMbps(block, RECEIVED_BITRATE_MBPS, RECEIVED_BYTES, RECEIVED_DURATION_MS);
            return block;
        }
    }
}
