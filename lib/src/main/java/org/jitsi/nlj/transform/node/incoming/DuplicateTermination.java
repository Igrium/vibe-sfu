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
import org.jitsi.utils.LRUCache;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A node which drops packets with SSRC and sequence number pairs identical to ones
 * that have previously been seen.
 *
 * (Since SRTP also has anti-replay protection, the normal case where duplicates
 * will occur is after the {@link RtxHandler}, since duplicate packets are sent over RTX for probing.)
 */
public class DuplicateTermination extends TransformerNode
{
    private final Map<Long, Set<Integer>> replayContexts = new TreeMap<>();
    private int numDuplicatePacketsDropped = 0;

    public DuplicateTermination()
    {
        super("Duplicate termination");
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();
        Set<Integer> replayContext = replayContexts.computeIfAbsent(
            rtpPacket.getSsrc(),
            k -> LRUCache.lruSet(1500, true)
        );

        if (!replayContext.add(rtpPacket.getSequenceNumber()))
        {
            numDuplicatePacketsDropped++;
            return null;
        }

        return packetInfo;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("num_duplicate_packets_dropped", numDuplicatePacketsDropped);
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_duplicate_packets_dropped", numDuplicatePacketsDropped);
        return json;
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
