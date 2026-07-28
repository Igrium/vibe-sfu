/*
 * Copyright @ 2019 - Present, 8x8 Inc
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

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.transform.node.TransformerNode;
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker;
import org.jitsi.rtp.rtcp.RtcpSrPacket;

/**
 * Updates RTCP Sender Reports with the current octet and packet count.
 */
public class RtcpSrUpdater extends TransformerNode
{
    private final OutgoingStatisticsTracker statsTracker;

    public RtcpSrUpdater(OutgoingStatisticsTracker statsTracker)
    {
        super("RtcpSrUpdater");
        this.statsTracker = statsTracker;
    }

    public OutgoingStatisticsTracker getStatsTracker()
    {
        return statsTracker;
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        // TODO support compound packets
        if (!(packetInfo.getPacket() instanceof RtcpSrPacket))
        {
            return packetInfo;
        }
        RtcpSrPacket rtcpSrPacket = (RtcpSrPacket) packetInfo.getPacket();

        // If we haven't sent RTP for this SSRC, drop the SR
        OutgoingStatisticsTracker.OutgoingSsrcStats.Snapshot ssrcStats =
            statsTracker.getSsrcSnapshot(rtcpSrPacket.getSenderSsrc());
        if (ssrcStats == null)
        {
            return null;
        }

        rtcpSrPacket.getSenderInfo().setSendersOctetCount(ssrcStats.getOctetCount());
        rtcpSrPacket.getSenderInfo().setSendersPacketCount(ssrcStats.getPacketCount());

        return packetInfo;
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
