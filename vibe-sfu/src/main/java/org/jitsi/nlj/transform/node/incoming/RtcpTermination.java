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
import org.jitsi.nlj.rtcp.RtcpEventNotifier;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.TransformerNode;
import org.jitsi.nlj.util.BufferPool;
import org.jitsi.rtp.rtcp.CompoundRtcpPacket;
import org.jitsi.rtp.rtcp.RtcpByePacket;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.RtcpRrPacket;
import org.jitsi.rtp.rtcp.RtcpSdesPacket;
import org.jitsi.rtp.rtcp.RtcpSrPacket;
import org.jitsi.rtp.rtcp.RtcpXrPacket;
import org.jitsi.rtp.rtcp.rtcpfb.UnsupportedRtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket;
import org.jitsi.utils.logging2.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

public class RtcpTermination extends TransformerNode
{
    private final RtcpEventNotifier rtcpEventNotifier;
    private final Logger logger;
    private final Map<String, Integer> packetReceiveCounts = new LinkedHashMap<>();

    /**
     * Number of packets we failed to forward because a compound packet contained more than one
     * packet we wanted to forward. Ideally this shouldn't happen.
     */
    private int numFailedToForward = 0;

    public RtcpTermination(RtcpEventNotifier rtcpEventNotifier, Logger parentLogger)
    {
        super("RTCP termination");
        this.rtcpEventNotifier = rtcpEventNotifier;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        CompoundRtcpPacket compoundRtcp = packetInfo.packetAs();
        RtcpPacket forwardedRtcp = null;

        for (RtcpPacket rtcpPacket : compoundRtcp.getPackets())
        {
            if (rtcpPacket instanceof RtcpFbPliPacket || rtcpPacket instanceof RtcpFbFirPacket
                || rtcpPacket instanceof RtcpSrPacket)
            {
                // We'll let these pass through and be forwarded to the conference (where they will be
                // routed to the other endpoint(s))
                // NOTE(brian): this should work fine as long as we can't receive 2 RTCP packets
                // we want to forward in the same compound packet.  If we can, then we may need
                // to turn this into a MultipleOutputNode
                if (forwardedRtcp != null)
                {
                    RtcpPacket previouslyForwarded = forwardedRtcp;
                    RtcpPacket currentPacket = rtcpPacket;
                    logger.info(() -> "Failed to forward a packet of type " +
                        previouslyForwarded.getClass().getSimpleName() + ". Replaced by " +
                        currentPacket.getClass().getSimpleName() + ".");
                    numFailedToForward++;
                }
                forwardedRtcp = rtcpPacket;
            }
            else if (rtcpPacket instanceof RtcpSdesPacket || rtcpPacket instanceof RtcpRrPacket
                || rtcpPacket instanceof RtcpFbNackPacket || rtcpPacket instanceof RtcpByePacket
                || rtcpPacket instanceof RtcpFbTccPacket || rtcpPacket instanceof RtcpFbRembPacket)
            {
                // Supported, but no special handling here (any special handling will be in
                // notifyRtcpReceived below
            }
            else if (rtcpPacket instanceof RtcpXrPacket)
            {
                // Unsupported, but we get them when chrome does screenshare and the
                // message below clouds up the logs.  They are still tracked as part
                // of the packetReceiveCount
            }
            else if (rtcpPacket instanceof UnsupportedRtcpFbPacket)
            {
                UnsupportedRtcpFbPacket unsupported = (UnsupportedRtcpFbPacket) rtcpPacket;
                logger.info(() -> "TODO: not yet handling RTCP packet of type " + unsupported.getPacketType() +
                    " fmt " + unsupported.getReportCount() + " " + unsupported.getClass());
            }
            else
            {
                RtcpPacket finalRtcpPacket = rtcpPacket;
                logger.info(() -> "TODO: not yet handling RTCP packet of type " + finalRtcpPacket.getPacketType() +
                    " " + finalRtcpPacket.getClass());
            }
            // TODO: keep an eye on if anything in here takes a while it could slow the packet pipeline down
            packetReceiveCounts.merge(rtcpPacket.getClass().getSimpleName(), 1, Integer::sum);
            rtcpEventNotifier.notifyRtcpReceived(rtcpPacket, packetInfo.getReceivedTime());

            if (forwardedRtcp instanceof RtcpSrPacket)
            {
                RtcpSrPacket sr = (RtcpSrPacket) forwardedRtcp;
                RtcpPacket finalRtcpPacket = rtcpPacket;
                logger.debug(() -> "Saw an sr from ssrc=" + finalRtcpPacket.getSenderSsrc() + ", timestamp=" +
                    sr.getSenderInfo().getRtpTimestamp());
                forwardedRtcp = sr.getReportCount() > 0 ? sr.cloneWithoutReportBlocks() : sr;
            }
        }

        if (forwardedRtcp == null)
        {
            return null;
        }
        if (forwardedRtcp.getBuffer() != packetInfo.getPacket().getBuffer())
        {
            // We're not using the original packet's buffer, so we can return it to the pool
            BufferPool.returnBuffer(packetInfo.getPacket().getBuffer());
        }
        packetInfo.setPacket(forwardedRtcp);
        return packetInfo;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        packetReceiveCounts.forEach((type, count) -> block.addNumber("num_" + type + "_rx", count));
        block.addNumber("num_failed_to_forward", numFailedToForward);
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_failed_to_forward", numFailedToForward);
        packetReceiveCounts.forEach((type, count) -> json.put("num_" + type + "_rx", count));
        return json;
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
