/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.bandwidthestimation2;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.PacketOrigin;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.nlj.util.RtpSequenceIndexTracker;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.PacketReport;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.TimeUtils;
import org.jitsi.utils.logging2.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** TransportFeedbackAdapter converts RTCP feedback packets to RTCP agnostic per
 * packet send/receive information.
 * It supports {@link RtcpFbCcfbPacket} according to RFC 8888 and
 * {@link RtcpFbTccPacket} according to
 * https://datatracker.ietf.org/doc/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
 *
 * Transport feedback adapter,
 * based loosely on WebRTC modules/congestion_controller/rtp/transport_feedback_adapter.{h,cc} in
 * WebRTC tag branch-heads/6613 (Chromium 128)
 * modified to use Jitsi types for objects outside the congestion controller,
 * and not using Network Routes.
 */
public class TransportFeedbackAdapter
{
    private static final Duration kSendTimeHistoryWindow = Duration.ofSeconds(60);

    public final Logger logger;

    public TransportFeedbackAdapter(Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    private static class PacketFeedback
    {
        // Time corresponding to when this object was created.
        final Instant creationTime;
        final SentPacket sent;
        final long ssrc;
        final int rtpSequenceNumber;
        final boolean isRetransmission;

        // Jitsi extension: whether the packet was previously reported lost
        boolean reportedLost = false;

        PacketFeedback(Instant creationTime, SentPacket sent, long ssrc, int rtpSequenceNumber,
            boolean isRetransmission)
        {
            this.creationTime = creationTime;
            this.sent = sent;
            this.ssrc = ssrc;
            this.rtpSequenceNumber = rtpSequenceNumber;
            this.isRetransmission = isRetransmission;
        }
    }

    private static class InFlightBytesTracker
    {
        DataSize inFlightData = DataSize.ZERO;

        void addInFlightPacketBytes(PacketFeedback packet)
        {
            if (!InstantKt.isFinite(packet.sent.sendTime))
            {
                throw new IllegalArgumentException("Require failed: packet.sent.sendTime.isFinite()");
            }
            inFlightData = inFlightData.plus(packet.sent.size);
        }

        void removeInFlightPacketBytes(PacketFeedback packet)
        {
            if (InstantKt.isInfinite(packet.sent.sendTime))
            {
                return;
            }
            if (packet.sent.size.compareTo(inFlightData) > 0)
            {
                throw new IllegalStateException("Check failed: packet.sent.size <= inFlightData");
            }
            inFlightData = inFlightData.minus(packet.sent.size);
        }

        DataSize getOutstandingData()
        {
            return inFlightData;
        }
    }

    public void addPacket(PacketInfo packet, long tccSeqNum, DataSize overheadBytes, Instant creationTime)
    {
        RtpPacket rtpPacket = packet.packetAs();
        // Update seqNumUnwrapper's notion of the ROC, and verify that it's in sync
        int truncatedSeqNum = (int) (tccSeqNum & 0xFFFF);
        long unwrappedSeqNum = seqNumUnwrapper.update(truncatedSeqNum);
        if (unwrappedSeqNum != tccSeqNum)
        {
            throw new IllegalStateException("Check failed: unwrappedSeqNum == tccSeqNum");
        }
        SentPacket sent = new SentPacket();
        sent.sequenceNumber = tccSeqNum;
        sent.size = DataSize.ofBytes(packet.getPacket().getLength()).plus(overheadBytes);
        sent.pacingInfo = packet.getProbingInfo() instanceof PacedPacketInfo
            ? (PacedPacketInfo) packet.getProbingInfo()
            : new PacedPacketInfo();
        PacketFeedback feedback = new PacketFeedback(
            creationTime,
            sent,
            rtpPacket.getSsrc(),
            rtpPacket.getSequenceNumber(),
            packet.getPacketOrigin() == PacketOrigin.Retransmission
        );
        while (!history.isEmpty() &&
            Duration.between(history.firstEntry().getValue().creationTime, creationTime)
                .compareTo(kSendTimeHistoryWindow) > 0)
        {
            // TODO(sprang): Warn if erasing (too many) old items?
            if (history.firstEntry().getValue().sent.sequenceNumber > lastAckSeqNum)
            {
                inFlight.removeInFlightPacketBytes(history.firstEntry().getValue());
            }
            PacketFeedback oldPacket = history.firstEntry().getValue();
            rtpToTransportSequenceNumber.remove(
                new SsrcAndRtpSequenceNumber(oldPacket.ssrc, oldPacket.rtpSequenceNumber)
            );
            history.remove(history.firstEntry().getKey());
        }
        // Note that it can happen that the same SSRC and sequence number is sent
        // again. e.g, audio retransmission.
        rtpToTransportSequenceNumber.put(
            new SsrcAndRtpSequenceNumber(feedback.ssrc, feedback.rtpSequenceNumber),
            feedback.sent.sequenceNumber);
        history.put(feedback.sent.sequenceNumber, feedback);
    }

    public SentPacket processSentPacket(SentPacketInfo sentPacket)
    {
        Instant sendTime = sentPacket.sendTime;
        // TODO(srte): Only use one way to indicate that packet feedback is used.
        if (sentPacket.info.includedInFeedback || sentPacket.packetId != -1L)
        {
            long seqNum = sentPacket.packetId;
            PacketFeedback it = history.get(seqNum);
            if (it != null)
            {
                boolean packetRetransmit = InstantKt.isFinite(it.sent.sendTime);
                it.sent.sendTime = sendTime;
                lastSendTime = InstantKt.max(lastSendTime, sendTime);
                // TODO(srte): Don't do this on retransmit.
                if (!pendingUntrackedSize.equals(DataSize.ZERO))
                {
                    if (sendTime.isBefore(lastUntrackedSendTime))
                    {
                        logger.warn(
                            "appending acknowledged data for out of order packet.  (Diff: "
                                + Duration.between(sendTime, lastUntrackedSendTime) + ".)");
                    }
                    pendingUntrackedSize = pendingUntrackedSize.plus(
                        DataSize.ofBytes(sentPacket.info.packetSizeBytes));
                }
                if (!packetRetransmit)
                {
                    if (it.sent.sequenceNumber > lastAckSeqNum)
                    {
                        inFlight.addInFlightPacketBytes(it);
                        it.sent.dataInFlight = inFlight.getOutstandingData();
                        return it.sent;
                    }
                }
            }
        }
        else if (sentPacket.info.includedInAllocation)
        {
            if (sendTime.isBefore(lastSendTime))
            {
                logger.warn("ignoring untracked data for out of order packet");
            }
            pendingUntrackedSize = pendingUntrackedSize.plus(DataSize.ofBytes(sentPacket.info.packetSizeBytes));
            lastUntrackedSendTime = InstantKt.max(lastUntrackedSendTime, sendTime);
        }
        return null;
    }

    public TransportPacketsFeedback processTransportFeedback(RtcpFbTccPacket feedback, Instant feedbackReceiveTime)
    {
        if (feedback.GetPacketStatusCount() == 0)
        {
            logger.info("Empty transport feedback packet received");
            return null;
        }

        // Add timestamp deltas to a local time base selected on first packet arrival.
        // This won't be the true time base, but makes it easier to manually inspect
        // time stamps.
        if (InstantKt.isInfinite(lastTransportFeedbackBaseTime))
        {
            currentOffset = feedbackReceiveTime;
        }
        else
        {
            Duration delta = feedback.GetBaseDelta(lastTransportFeedbackBaseTime);
            // Protect against assigning current_offset_ negative value.
            if (delta.compareTo(Duration.between(currentOffset, Instant.EPOCH)) < 0)
            {
                logger.warn("Unexpected feedback timestamp received: " + feedbackReceiveTime);
                currentOffset = feedbackReceiveTime;
            }
            else
            {
                currentOffset = currentOffset.plus(delta);
            }
        }
        lastTransportFeedbackBaseTime = feedback.BaseTime();

        ArrayList<PacketResult> packetResultVector = new ArrayList<>();
        packetResultVector.ensureCapacity(feedback.GetPacketStatusCount());

        int failedLookups = 0;

        Duration deltaSinceBase = Duration.ZERO;

        for (PacketReport report : feedback)
        {
            long seqNum = seqNumUnwrapper.interpret(report.getSeqNum());

            PacketFeedback packetFeedback =
                retrievePacketFeedback(seqNum, report instanceof ReceivedPacketReport);
            if (packetFeedback == null)
            {
                ++failedLookups;
                continue;
            }

            boolean previouslyReportedLost = packetFeedback.reportedLost;

            PacketResult result = new PacketResult();
            result.sentPacket = packetFeedback.sent;

            if (report instanceof ReceivedPacketReport)
            {
                deltaSinceBase = deltaSinceBase.plus(((ReceivedPacketReport) report).getDeltaDuration());
                result.receiveTime = currentOffset.plus(deltaSinceBase);
                history.remove(seqNum);
            }
            else
            {
                packetFeedback.reportedLost = true;
            }
            result.rtpPacketInfo = new PacketResult.RtpPacketInfo(
                packetFeedback.ssrc,
                packetFeedback.rtpSequenceNumber,
                packetFeedback.isRetransmission
            );
            result.previouslyReportedLost = previouslyReportedLost;
            packetResultVector.add(result);
        }

        if (failedLookups > 0)
        {
            logger.info(
                "Failed to lookup send time for " + failedLookups + " packet" + (failedLookups > 1 ? "s" : "")
                    + ". Packets reordered or send time history too small?");
        }

        return toTransportFeedback(packetResultVector, feedbackReceiveTime, false);
    }

    public TransportPacketsFeedback processCongestionControlFeedback(
        RtcpFbCcfbPacket feedback,
        Instant feedbackReceiveTime)
    {
        if (feedback.getPackets().isEmpty())
        {
            logger.info("Empty congestion control feedback packet received");
            return null;
        }
        if (InstantKt.isInfinite(currentOffset))
        {
            currentOffset = feedbackReceiveTime;
        }
        Duration feedbackDelta;
        if (lastFeedbackCompactNtpTime != null)
        {
            feedbackDelta = Duration.ofMillis(
                TimeUtils.ntpShortToMs(feedback.getReportTimestampCompactNtp())
                    - TimeUtils.ntpShortToMs(lastFeedbackCompactNtpTime));
        }
        else
        {
            feedbackDelta = Duration.ZERO;
        }
        lastFeedbackCompactNtpTime = feedback.getReportTimestampCompactNtp();
        if (feedbackDelta.compareTo(Duration.ZERO) < 0)
        {
            logger.warn("Unexpected feedback ntp time delta " + feedbackDelta);
            currentOffset = feedbackReceiveTime;
        }
        else
        {
            currentOffset = currentOffset.plus(feedbackDelta);
        }

        int failedLookups = 0;
        boolean supportsEcn = true;
        List<PacketResult> packetResultVector = new ArrayList<>();
        for (org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.PacketInfo packetInfo : feedback.getPackets())
        {
            PacketFeedback packetFeedback = retrievePacketFeedback(
                new SsrcAndRtpSequenceNumber(packetInfo.getSsrc(), packetInfo.getSequenceNumber()),
                packetInfo instanceof ReceivedPacketInfo
            );
            if (packetFeedback == null)
            {
                ++failedLookups;
                continue;
            }
            PacketResult result = new PacketResult();
            result.sentPacket = packetFeedback.sent;
            if (packetInfo instanceof ReceivedPacketInfo)
            {
                ReceivedPacketInfo receivedPacketInfo = (ReceivedPacketInfo) packetInfo;
                result.receiveTime = currentOffset.minus(receivedPacketInfo.getArrivalTimeOffset());
                result.ecn = receivedPacketInfo.getEcn();
                supportsEcn = supportsEcn && receivedPacketInfo.getEcn() != EcnMarking.kNotEct;
            }
            result.rtpPacketInfo = new PacketResult.RtpPacketInfo(
                packetFeedback.ssrc,
                packetFeedback.rtpSequenceNumber,
                packetFeedback.isRetransmission
            );
            packetResultVector.add(result);
        }

        if (failedLookups > 0)
        {
            final int failedLookupsFinal = failedLookups;
            logger.warn(() ->
                "Failed to lookup send time for " + failedLookupsFinal + " packets. "
                    + "Packets reordered or send time history too small?");
        }

        // Feedback is expected to be sorted in send order.
        packetResultVector.sort(Comparator.comparingLong(result -> result.sentPacket.sequenceNumber));

        return toTransportFeedback(packetResultVector, feedbackReceiveTime, supportsEcn);
    }

    public DataSize getOutstandingData()
    {
        return inFlight.getOutstandingData();
    }

    private static class SsrcAndRtpSequenceNumber implements Comparable<SsrcAndRtpSequenceNumber>
    {
        final long ssrc;
        final int rtpSequenceNumber;

        SsrcAndRtpSequenceNumber(long ssrc, int rtpSequenceNumber)
        {
            this.ssrc = ssrc;
            this.rtpSequenceNumber = rtpSequenceNumber;
        }

        @Override
        public int compareTo(SsrcAndRtpSequenceNumber other)
        {
            int cmp = Long.compare(ssrc, other.ssrc);
            if (cmp != 0)
            {
                return cmp;
            }
            return Integer.compare(rtpSequenceNumber, other.rtpSequenceNumber);
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof SsrcAndRtpSequenceNumber))
            {
                return false;
            }
            SsrcAndRtpSequenceNumber other = (SsrcAndRtpSequenceNumber) o;
            return ssrc == other.ssrc && rtpSequenceNumber == other.rtpSequenceNumber;
        }

        @Override
        public int hashCode()
        {
            return Long.hashCode(ssrc) * 31 + Integer.hashCode(rtpSequenceNumber);
        }
    }

    private TransportPacketsFeedback toTransportFeedback(
        List<PacketResult> packetResults,
        Instant feedbackReceiveTime,
        boolean supportsEcn)
    {
        TransportPacketsFeedback msg = new TransportPacketsFeedback();
        msg.feedbackTime = feedbackReceiveTime;
        if (packetResults.isEmpty())
        {
            return null;
        }
        msg.packetFeedbacks = packetResults;
        msg.dataInFlight = inFlight.getOutstandingData();
        msg.transportSupportsEcn = supportsEcn;

        return msg;
    }

    private PacketFeedback retrievePacketFeedback(long transportSeqNum, boolean received)
    {
        if (transportSeqNum > lastAckSeqNum)
        {
            // Starts at history_.begin() if last_ack_seq_num_ < 0, since any
            // valid sequence number is >= 0.
            Iterator<Map.Entry<Long, PacketFeedback>> it =
                history.subMap(lastAckSeqNum, false, transportSeqNum, true).entrySet().iterator();
            while (it.hasNext())
            {
                inFlight.removeInFlightPacketBytes(it.next().getValue());
            }
            lastAckSeqNum = transportSeqNum;
        }

        PacketFeedback packetFeedback = history.get(transportSeqNum);

        if (packetFeedback == null)
        {
            logger.debug(() -> "No history entry found for seqNum " + transportSeqNum);
            return null;
        }

        if (InstantKt.isInfinite(packetFeedback.sent.sendTime))
        {
            // TODO(srte): Fix the tests that makes this happen and make this a
            //  DCHECK.
            logger.error(
                "Received feedback before packet with seqNum " + transportSeqNum + " was indicated as sent");
            return null;
        }

        if (received)
        {
            // Note: Lost packets are not removed from history because they might
            // be reported as received by a later feedback.
            rtpToTransportSequenceNumber.remove(
                new SsrcAndRtpSequenceNumber(packetFeedback.ssrc, packetFeedback.rtpSequenceNumber)
            );
            history.remove(transportSeqNum);
        }

        return packetFeedback;
    }

    private PacketFeedback retrievePacketFeedback(SsrcAndRtpSequenceNumber key, boolean received)
    {
        Long transportSeqNum = rtpToTransportSequenceNumber.get(key);
        if (transportSeqNum == null)
        {
            return null;
        }
        return retrievePacketFeedback(transportSeqNum, received);
    }

    private DataSize pendingUntrackedSize = DataSize.ZERO;
    private Instant lastSendTime = Instant.MIN;
    private Instant lastUntrackedSendTime = Instant.MIN;
    private final RtpSequenceIndexTracker seqNumUnwrapper = new RtpSequenceIndexTracker();

    private long lastAckSeqNum = -1L;
    private final InFlightBytesTracker inFlight = new InFlightBytesTracker();

    private Instant currentOffset = Instant.MIN;

    // `last_transport_feedback_base_time` is only used for transport feedback to
    // track base time.
    private Instant lastTransportFeedbackBaseTime = Instant.MIN;

    // Used by RFC 8888 congestion control feedback to track base time.
    private Long lastFeedbackCompactNtpTime = null;

    // Map SSRC and RTP sequence number to transport sequence number.
    private final TreeMap<SsrcAndRtpSequenceNumber, Long> rtpToTransportSequenceNumber = new TreeMap<>();
    private final TreeMap<Long, PacketFeedback> history = new TreeMap<>();

    /** Jitsi local */
    public StatisticsSnapshot getStatisitics()
    {
        return new StatisticsSnapshot(
            inFlight.inFlightData,
            pendingUntrackedSize,
            lastSendTime,
            lastUntrackedSendTime,
            lastAckSeqNum,
            history.size(),
            currentOffset,
            lastTransportFeedbackBaseTime
        );
    }

    public static class StatisticsSnapshot
    {
        public final DataSize inFlight;
        public final DataSize pendingUntrackedSize;
        public final Instant lastSendTime;
        public final Instant lastUntrackedSendTime;
        public final long lastAckSeqNum;
        public final int historySize;
        public final Instant currentOffset;
        public final Instant lastTransportFeedbackBaseTime;

        public StatisticsSnapshot(
            DataSize inFlight,
            DataSize pendingUntrackedSize,
            Instant lastSendTime,
            Instant lastUntrackedSendTime,
            long lastAckSeqNum,
            int historySize,
            Instant currentOffset,
            Instant lastTransportFeedbackBaseTime)
        {
            this.inFlight = inFlight;
            this.pendingUntrackedSize = pendingUntrackedSize;
            this.lastSendTime = lastSendTime;
            this.lastUntrackedSendTime = lastUntrackedSendTime;
            this.lastAckSeqNum = lastAckSeqNum;
            this.historySize = historySize;
            this.currentOffset = currentOffset;
            this.lastTransportFeedbackBaseTime = lastTransportFeedbackBaseTime;
        }

        public ObjectNode toJson()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("in_flight_bytes", inFlight.getBytes());
            node.put("pending_untracked_size", pendingUntrackedSize.getBytes());
            node.put("last_send_time", toEpochMilliOrInf(lastSendTime).toString());
            node.put("last_untracked_send_time", toEpochMilliOrInf(lastUntrackedSendTime).toString());
            node.put("last_ack_seq_num", lastAckSeqNum);
            node.put("history_size", historySize);
            node.put("current_offset", toEpochMilliOrInf(currentOffset).toString());
            node.put("last_transport_feedback_base_time",
                toEpochMilliOrInf(lastTransportFeedbackBaseTime).toString());
            return node;
        }
    }

    private static Number toEpochMilliOrInf(Instant instant)
    {
        try
        {
            return instant.toEpochMilli();
        }
        catch (ArithmeticException e)
        {
            if (instant.isBefore(Instant.EPOCH))
            {
                return Double.NEGATIVE_INFINITY;
            }
            else
            {
                return Double.POSITIVE_INFINITY;
            }
        }
    }
}
