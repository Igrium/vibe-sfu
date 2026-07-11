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
package org.jitsi.nlj.transform.node.outgoing;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.RtxPayloadType;
import org.jitsi.nlj.rtp.RtxPacket;
import org.jitsi.nlj.rtp.SsrcAssociationType;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ModifierNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging2.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RetransmissionSender extends ModifierNode
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Logger logger;

    /**
     * Maps an original payload type (Int) to its {@link RtxPayloadType}
     */
    private final Map<Integer, RtxPayloadType> origPtToRtxPayloadType = new ConcurrentHashMap<>();

    /**
     * A map of rtx stream ssrc to the current sequence number for that stream
     */
    private final Map<Long, Integer> rtxStreamSeqNums = new HashMap<>();

    private int numRetransmissionsRequested = 0;
    private int numRetransmittedRtxPackets = 0;
    private int numRetransmittedPlainPackets = 0;

    public RetransmissionSender(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        super("Retransmission sender");
        this.streamInformationStore = streamInformationStore;
        this.logger = parentLogger.createChildLogger(RetransmissionSender.class.getName());

        streamInformationStore.onRtpPayloadTypesChanged(currentRtpPayloadTypes -> {
            origPtToRtxPayloadType.clear();
            for (PayloadType pt : currentRtpPayloadTypes.values())
            {
                if (pt instanceof RtxPayloadType)
                {
                    RtxPayloadType rtxPayloadType = (RtxPayloadType) pt;
                    origPtToRtxPayloadType.put(rtxPayloadType.getAssociatedPayloadType(), rtxPayloadType);
                }
            }
        });
    }

    /**
     * Transform an original RTP packet into an RTX-encapsulated form of that packet.
     */
    @Override
    protected PacketInfo modify(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();
        numRetransmissionsRequested++;
        RtxPayloadType rtxPt = origPtToRtxPayloadType.get(rtpPacket.getPayloadType());
        if (rtxPt == null)
        {
            return retransmitPlain(packetInfo);
        }
        Long rtxSsrc = streamInformationStore.getRemoteSecondarySsrc(rtpPacket.getSsrc(), SsrcAssociationType.RTX);
        if (rtxSsrc == null)
        {
            return retransmitPlain(packetInfo);
        }

        return retransmitRtx(packetInfo, Unsigned.toPositiveInt(rtxPt.getPt()), rtxSsrc);
    }

    private PacketInfo retransmitRtx(PacketInfo packetInfo, int rtxPt, long rtxSsrc)
    {
        // Get a default value of 1 to start if it isn't present in the map.  If it is present
        // in the map, get the value and increment it by 1
        int rtxSeqNum = rtxStreamSeqNums.merge(rtxSsrc, 1, Integer::sum);
        RtpPacket rtpPacket = packetInfo.packetAs();
        logger.debug(() -> hashCode() + " sending RTX packet with ssrc " + rtxSsrc + " with pt " + rtxPt +
            " and seqNum " + rtxSeqNum + " with original ssrc " + rtpPacket.getSsrc() +
            ", original sequence number " + rtpPacket.getSequenceNumber() + " and original payload type: " +
            rtpPacket.getPayloadType());
        RtxPacket.addOriginalSequenceNumber(rtpPacket);
        rtpPacket.setSsrc(rtxSsrc);
        rtpPacket.setPayloadType(rtxPt);
        rtpPacket.setSequenceNumber(rtxSeqNum);

        packetInfo.resetPayloadVerification();
        numRetransmittedRtxPackets++;

        return packetInfo;
    }

    private PacketInfo retransmitPlain(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();
        logger.debug(() -> hashCode() + " plain retransmission packet with original ssrc " + rtpPacket.getSsrc() +
            ", original sequence number " + rtpPacket.getSequenceNumber() + " and original payload type: " +
            rtpPacket.getPayloadType());

        numRetransmittedPlainPackets++;
        // No work needed
        return packetInfo;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("num_retransmissions_requested", numRetransmissionsRequested);
        block.addNumber("num_retransmissions_rtx_sent", numRetransmittedRtxPackets);
        block.addNumber("num_retransmissions_plain_sent", numRetransmittedPlainPackets);
        block.addString("rtx_payload_types(orig -> rtx)", origPtToRtxPayloadType.toString());
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_retransmissions_requested", numRetransmissionsRequested);
        json.put("num_retransmissions_rtx_sent", numRetransmittedRtxPackets);
        json.put("num_retransmissions_plain_sent", numRetransmittedPlainPackets);
        return json;
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }
}
