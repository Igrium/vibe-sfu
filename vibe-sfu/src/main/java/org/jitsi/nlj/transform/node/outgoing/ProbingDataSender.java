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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.DebugStateMode;
import org.jitsi.nlj.Event;
import org.jitsi.nlj.EventHandler;
import org.jitsi.nlj.PacketHandler;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.PacketOrigin;
import org.jitsi.nlj.SetLocalSsrcEvent;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.RtxPayloadType;
import org.jitsi.nlj.format.VideoPayloadType;
import org.jitsi.nlj.rtp.PaddingVideoPacket;
import org.jitsi.nlj.util.PacketCache;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.rtp.RtpHeader;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.MediaType;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ProbingDataSender} currently supports probing via 2 methods:
 * 1) retransmitting previous packets via RTX via {@link #sendRedundantDataOverRtx}.
 * 2) If RTX is not available, or, not enough packets to retransmit are available, we
 * can send empty media packets using the bridge's ssrc
 *
 */
public class ProbingDataSender implements EventHandler
{
    public static final Random random = new Random();

    private final PacketCache packetCache;
    private final PacketHandler rtxDataSender;
    private final PacketHandler garbageDataSender;
    private final DiagnosticContext diagnosticContext;

    private final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(this.getClass());
    private final Logger logger;

    private boolean rtxSupported = false;
    private final Set<VideoPayloadType> videoPayloadTypes = ConcurrentHashMap.newKeySet();
    private Long localVideoSsrc = null;

    // Stats
    private long numProbingBytesSentRtx = 0;
    private long numProbingBytesSentDummyData = 0;

    private Collection<Long> lastMediaSsrcs = Collections.emptyList();

    public ProbingDataSender(
        PacketCache packetCache,
        PacketHandler rtxDataSender,
        PacketHandler garbageDataSender,
        DiagnosticContext diagnosticContext,
        ReadOnlyStreamInformationStore streamInformationStore,
        Logger parentLogger)
    {
        this.packetCache = packetCache;
        this.rtxDataSender = rtxDataSender;
        this.garbageDataSender = garbageDataSender;
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(ProbingDataSender.class.getName());

        streamInformationStore.onRtpPayloadTypesChanged(currentRtpPayloadTypes -> {
            if (currentRtpPayloadTypes.isEmpty())
            {
                videoPayloadTypes.clear();
                rtxSupported = false;
            }
            else
            {
                for (PayloadType pt : currentRtpPayloadTypes.values())
                {
                    if (!rtxSupported && pt instanceof RtxPayloadType)
                    {
                        rtxSupported = true;
                        logger.debug(() -> "RTX payload type signaled, enabling RTX probing");
                    }
                    if (pt instanceof VideoPayloadType)
                    {
                        videoPayloadTypes.add((VideoPayloadType) pt);
                    }
                }
            }
        });
    }

    public int sendProbing(Collection<Long> mediaSsrcsIn, int numBytes, Object probingInfo)
    {
        int totalBytesSent = 0;

        Collection<Long> mediaSsrcs;
        if (mediaSsrcsIn != null)
        {
            lastMediaSsrcs = mediaSsrcsIn;
            mediaSsrcs = mediaSsrcsIn;
        }
        else
        {
            mediaSsrcs = lastMediaSsrcs;
        }

        if (rtxSupported)
        {
            for (Long mediaSsrc : mediaSsrcs)
            {
                if (totalBytesSent >= numBytes)
                {
                    break;
                }
                int rtxBytesSent = sendRedundantDataOverRtx(mediaSsrc, numBytes - totalBytesSent, probingInfo);
                numProbingBytesSentRtx += rtxBytesSent;
                totalBytesSent += rtxBytesSent;
                if (timeSeriesLogger.isTraceEnabled())
                {
                    timeSeriesLogger.trace(
                        diagnosticContext
                            .makeTimeSeriesPoint("rtx_probing_bytes")
                            .addField("ssrc", mediaSsrc)
                            .addField("bytes", rtxBytesSent)
                    );
                }
            }
        }
        if (totalBytesSent < numBytes)
        {
            int dummyBytesSent = sendDummyData(numBytes - totalBytesSent, probingInfo);
            numProbingBytesSentDummyData += dummyBytesSent;
            totalBytesSent += dummyBytesSent;
            if (timeSeriesLogger.isTraceEnabled())
            {
                timeSeriesLogger.trace(
                    diagnosticContext
                        .makeTimeSeriesPoint("dummy_probing_bytes")
                        .addField("bytes", dummyBytesSent)
                );
            }
        }

        return totalBytesSent;
    }

    /**
     * Using the RTX stream associated with {@code mediaSsrc}, send {@code numBytes} of data
     * by re-transmitting previously sent packets from the outgoing packet cache.
     * Returns the number of bytes transmitted
     */
    private int sendRedundantDataOverRtx(long mediaSsrc, int numBytes, Object probingInfo)
    {
        int bytesSent = 0;
        // TODO(brian): we're in a thread context mess here.  we'll be sending these out from the bandwidthprobing
        // context (or whoever calls this) which i don't think we want.  Need look at getting all the pipeline
        // work posted to one thread so we don't have to worry about concurrency nightmares

        // Get the most recent packets whose length add up to no more than numBytes.
        Set<RtpPacket> packets = packetCache.getMany(mediaSsrc, numBytes);
        for (RtpPacket packet : packets)
        {
            bytesSent += packet.getLength();
            PacketInfo packetInfo = new PacketInfo(packet);
            packetInfo.setProbingInfo(probingInfo);
            packetInfo.setPacketOrigin(probingInfo != null ? PacketOrigin.Probing : PacketOrigin.Padding);
            rtxDataSender.processPacket(packetInfo);
        }
        return bytesSent;
    }

    private long currDummyTimestamp = random.nextLong() & 0xFFFFFFFFL;
    private int currDummySeqNum = random.nextInt(0xFFFF);

    private int sendDummyData(int numBytes, Object probingInfo)
    {
        int bytesSent = 0;
        VideoPayloadType pt = videoPayloadTypes.stream().findFirst().orElse(null);
        if (pt == null)
        {
            return bytesSent;
        }
        Long senderSsrc = localVideoSsrc;
        if (senderSsrc == null)
        {
            return bytesSent;
        }

        while (bytesSent < numBytes)
        {
            int remainingBytes = numBytes - bytesSent;
            if (remainingBytes < RtpHeader.FIXED_HEADER_SIZE_BYTES)
            {
                break;
            }
            int paddingSize = Math.min(remainingBytes - RtpHeader.FIXED_HEADER_SIZE_BYTES, 0xFF);
            int packetLength = RtpHeader.FIXED_HEADER_SIZE_BYTES + paddingSize;

            PaddingVideoPacket paddingPacket = PaddingVideoPacket.create(packetLength);
            paddingPacket.setPayloadType(Unsigned.toPositiveInt(pt.getPt()));
            paddingPacket.setSsrc(senderSsrc);
            paddingPacket.setTimestamp(currDummyTimestamp);
            paddingPacket.setSequenceNumber(currDummySeqNum);
            PacketInfo packetInfo = new PacketInfo(paddingPacket);
            packetInfo.setProbingInfo(probingInfo);
            packetInfo.setPacketOrigin(probingInfo != null ? PacketOrigin.Probing : PacketOrigin.Padding);
            garbageDataSender.processPacket(packetInfo);

            currDummySeqNum++;
            bytesSent += packetLength;
        }
        currDummyTimestamp += 3000;

        return bytesSent;
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event instanceof SetLocalSsrcEvent)
        {
            SetLocalSsrcEvent setLocalSsrcEvent = (SetLocalSsrcEvent) event;
            if (MediaType.VIDEO.equals(setLocalSsrcEvent.getMediaType()))
            {
                logger.debug(() -> "Setting video ssrc to " + setLocalSsrcEvent.getSsrc());
                localVideoSsrc = setLocalSsrcEvent.getSsrc();
            }
        }
    }

    public ObjectNode debugState(DebugStateMode mode)
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("num_bytes_of_probing_data_sent_as_rtx", numProbingBytesSentRtx);
        o.put("num_bytes_of_probing_data_sent_as_dummy", numProbingBytesSentDummyData);
        o.put("rtx_supported", rtxSupported);
        if (mode == DebugStateMode.FULL)
        {
            o.put("local_video_ssrc", String.valueOf(localVideoSsrc));
            o.put("curr_dummy_timestamp", String.valueOf(currDummyTimestamp));
            o.put("curr_dummy_seq_num", String.valueOf(currDummySeqNum));
            o.put("video_payload_types", videoPayloadTypes.toString());
        }
        return o;
    }
}
