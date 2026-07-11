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

package org.jitsi.nlj.transform.node.outgoing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.rtp.rtcp.RtcpPacket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SentRtcpStats extends ObserverNode
{
    private final Map<String, Integer> sentRtcpCounts = new ConcurrentHashMap<>();

    public SentRtcpStats()
    {
        super("Sent RTCP stats");
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        RtcpPacket rtcpPacket = packetInfo.packetAs();
        sentRtcpCounts.merge(rtcpPacket.getClass().getSimpleName(), 1, Integer::sum);
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        sentRtcpCounts.forEach((rtcpType, count) -> block.addNumber("num_" + rtcpType + "_tx", count));
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        sentRtcpCounts.forEach((rtcpType, count) -> json.put("num_" + rtcpType + "_tx", count));
        return json;
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
