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
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.RtxPayloadType;
import org.jitsi.nlj.rtp.RtxPacket;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.TransformerNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging2.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Handle incoming RTX packets to strip the RTX information and make them
 * look like their original packets.
 * https://tools.ietf.org/html/rfc4588
 */
public class RtxHandler extends TransformerNode
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Logger logger;
    private int numPaddingPacketsReceived = 0;
    private int numRtxPacketsReceived = 0;

    /**
     * Maps the Integer payload type of RTX to the {@link RtxPayloadType} instance.  We do this
     * so we can look up the associated (original) payload type from the {@link RtxPayloadType}
     * instance quickly via the Int RTX payload type in an incoming RTX packet.
     */
    private final ConcurrentHashMap<Integer, RtxPayloadType> rtxPtToRtxPayloadType = new ConcurrentHashMap<>();

    public RtxHandler(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        super("RTX handler");
        this.streamInformationStore = streamInformationStore;
        this.logger = parentLogger.createChildLogger(getClass().getName());

        streamInformationStore.onRtpPayloadTypesChanged(currentPayloadTypes -> {
            rtxPtToRtxPayloadType.clear();
            for (PayloadType payloadType : currentPayloadTypes.values())
            {
                if (payloadType instanceof RtxPayloadType)
                {
                    RtxPayloadType rtxPayloadType = (RtxPayloadType) payloadType;
                    rtxPtToRtxPayloadType.put(Unsigned.toPositiveInt(rtxPayloadType.getPt()), rtxPayloadType);
                }
            }
        });
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();
        RtxPayloadType rtxPayloadType = rtxPtToRtxPayloadType.get(rtpPacket.getPayloadType());
        if (rtxPayloadType == null)
        {
            return packetInfo;
        }
        Long originalSsrc = streamInformationStore.getLocalPrimarySsrc(rtpPacket.getSsrc());
        if (originalSsrc == null)
        {
            return packetInfo;
        }
        if (packetInfo.isShouldDiscard())
        {
            /* Don't try to interpret an rtx shouldDiscard packet - SrtpTransformer may have skipped decrypting
             * it, which means its originalSeqNum is gibberish.
             * This doesn't need to wait until DiscardableDiscarder, because we don't care about continuity of
             * RTX sequence numbers.
             */
            return null;
        }
        // We do this check only after verifying we determine it's an RTX packet by finding
        // the associated payload type and SSRC above
        if (rtpPacket.getPayloadLength() - rtpPacket.getPaddingSize() < 2)
        {
            logger.debug(() -> "RTX packet is padding, ignore");
            numPaddingPacketsReceived++;
            return null;
        }
        int originalSeqNum = RtxPacket.getOriginalSequenceNumber(rtpPacket);
        int originalPt = rtxPayloadType.getAssociatedPayloadType();

        // Move the payload 2 bytes to the left
        RtxPacket.removeOriginalSequenceNumber(rtpPacket);
        rtpPacket.setSequenceNumber(originalSeqNum);
        rtpPacket.setPayloadType(originalPt);
        rtpPacket.setSsrc(originalSsrc);

        long finalOriginalSsrc = originalSsrc;
        logger.debug(() -> "Recovered RTX packet.  Original packet: " + finalOriginalSsrc + " " + originalSeqNum);
        numRtxPacketsReceived++;
        packetInfo.resetPayloadVerification();
        return packetInfo;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("num_rtx_packets_received", numRtxPacketsReceived);
        block.addNumber("num_padding_packets_received", numPaddingPacketsReceived);
        block.addString("rtx_payload_types", rtxPtToRtxPayloadType.values().toString());
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_rtx_packets_received", numRtxPacketsReceived);
        json.put("num_padding_packets_received", numPaddingPacketsReceived);
        return json;
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
