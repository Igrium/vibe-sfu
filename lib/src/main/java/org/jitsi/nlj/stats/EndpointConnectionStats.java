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
package org.jitsi.nlj.stats;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.rtcp.RtcpListener;
import org.jitsi.nlj.rtp.LossTracker;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.RtcpReportBlock;
import org.jitsi.rtp.rtcp.RtcpRrPacket;
import org.jitsi.rtp.rtcp.RtcpSrPacket;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.LRUCache;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks stats which are not necessarily tied to send or receive but the endpoint overall
 */
public class EndpointConnectionStats implements RtcpListener
{
    /**
     * The maximum number of SR packets and their timestamps to save.
     */
    private static final int MAX_SR_TIMESTAMP_HISTORY = 200;

    private final Clock clock;

    private final List<EndpointConnectionStatsListener> endpointConnectionStatsListeners = new CopyOnWriteArrayList<>();

    // Per-SSRC, maps the compacted NTP timestamp found in an SR SenderInfo to
    //  the clock time at which it was transmitted
    private final Map<SsrcAndTimestamp, Instant> srSentTimes =
        Collections.synchronizedMap(new LRUCache<>(MAX_SR_TIMESTAMP_HISTORY));
    private final Logger logger;

    private final Object lock = new Object();

    /**
     * The calculated RTT, in milliseconds, between the bridge and the endpoint
     */
    private double rtt = 0.0;

    private final LossTracker incomingLossTracker = new LossTracker();
    private final LossTracker outgoingLossTracker = new LossTracker();

    public EndpointConnectionStats(Logger parentLogger)
    {
        this(parentLogger, Clock.systemUTC());
    }

    public EndpointConnectionStats(Logger parentLogger, Clock clock)
    {
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(EndpointConnectionStats.class.getName());
    }

    public LossTracker getIncomingLossTracker()
    {
        return incomingLossTracker;
    }

    public LossTracker getOutgoingLossTracker()
    {
        return outgoingLossTracker;
    }

    public void addListener(EndpointConnectionStatsListener listener)
    {
        endpointConnectionStatsListeners.add(listener);
    }

    public void removeListener(EndpointConnectionStatsListener listener)
    {
        endpointConnectionStatsListeners.remove(listener);
    }

    public Snapshot getSnapshot()
    {
        synchronized (lock)
        {
            return new Snapshot(rtt, incomingLossTracker.getSnapshot(), outgoingLossTracker.getSnapshot());
        }
    }

    @Override
    public void rtcpPacketReceived(RtcpPacket packet, Instant receivedTime)
    {
        if (packet instanceof RtcpSrPacket)
        {
            RtcpSrPacket srPacket = (RtcpSrPacket) packet;
            logger.debug(() -> "Received SR packet with " + srPacket.getReportBlocks().size() + " report blocks");
            for (RtcpReportBlock reportBlock : srPacket.getReportBlocks())
            {
                processReportBlock(receivedTime, reportBlock);
            }
        }
        else if (packet instanceof RtcpRrPacket)
        {
            RtcpRrPacket rrPacket = (RtcpRrPacket) packet;
            logger.debug(() -> "Received RR packet with " + rrPacket.getReportBlocks().size() + " report blocks");
            for (RtcpReportBlock reportBlock : rrPacket.getReportBlocks())
            {
                processReportBlock(receivedTime, reportBlock);
            }
        }
    }

    @Override
    public void rtcpPacketSent(RtcpPacket packet)
    {
        if (packet instanceof RtcpSrPacket)
        {
            RtcpSrPacket srPacket = (RtcpSrPacket) packet;
            logger.debug(() -> "Tracking sent SR packet with compacted timestamp " +
                srPacket.getSenderInfo().getCompactedNtpTimestamp());
            SsrcAndTimestamp entry =
                new SsrcAndTimestamp(srPacket.getSenderSsrc(), srPacket.getSenderInfo().getCompactedNtpTimestamp());
            srSentTimes.put(entry, clock.instant());
        }
    }

    private void processReportBlock(Instant receivedTime, RtcpReportBlock reportBlock)
    {
        synchronized (lock)
        {
            if (reportBlock.getLastSrTimestamp() == 0L && reportBlock.getDelaySinceLastSr() == 0L)
            {
                logger.debug(() -> "Report block for ssrc " + reportBlock.getSsrc() + " didn't have SR data: " +
                    "lastSrTimestamp was " + reportBlock.getLastSrTimestamp() + ", " +
                    "delaySinceLastSr was " + reportBlock.getDelaySinceLastSr());
                return;
            }
            if (receivedTime == null)
            {
                logger.debug(() -> "Arrival time of report block is null, cannot calculate RTT");
                return;
            }
            // We need to know when we sent the last SR
            Instant srSentTime =
                srSentTimes.get(new SsrcAndTimestamp(reportBlock.getSsrc(), reportBlock.getLastSrTimestamp()));
            if (srSentTime == null)
            {
                logger.debug(() -> "No sent SR found for SSRC " + reportBlock.getSsrc() + " and SR " +
                    "timestamp " + reportBlock.getLastSrTimestamp());
                return;
            }

            // The delaySinceLastSr value is given in 1/65536ths of a second, so divide it by .000065536 to get it
            // in nanoseconds
            Duration remoteProcessingDelay =
                Duration.ofNanos((long) (reportBlock.getDelaySinceLastSr() / .000065536));
            if (remoteProcessingDelay.compareTo(Duration.ofMinutes(5)) > 0)
            {
                logger.warn("Ignoring report block with suspiciously long DLSR: " + remoteProcessingDelay);
                return;
            }

            double newRtt =
                DurationKt.toDoubleMillis(Duration.between(srSentTime, receivedTime).minus(remoteProcessingDelay));
            if (newRtt > DurationKt.toDoubleMillis(DurationKt.getSecs(7)))
            {
                logger.warn(
                    "Ignoring suspiciously high rtt value: " + newRtt + " ms, remote processing delay was " +
                        remoteProcessingDelay + " (" + reportBlock.getDelaySinceLastSr() + "), srSentTime was " +
                        srSentTime + ", received time was " + receivedTime
                );
                return;
            }
            if (newRtt < 0)
            {
                logger.warn(
                    "Negative rtt value: " + newRtt + " ms, remote processing delay was " +
                        remoteProcessingDelay + " (" + reportBlock.getDelaySinceLastSr() + "), srSentTime was " +
                        srSentTime + ", received time was " + receivedTime
                );
                return;
            }

            rtt = newRtt;
            for (EndpointConnectionStatsListener listener : endpointConnectionStatsListeners)
            {
                listener.onRttUpdate(rtt);
            }
        }
    }

    public interface EndpointConnectionStatsListener
    {
        void onRttUpdate(double newRttMs);
    }

    public static class Snapshot
    {
        private final double rtt;
        private final LossTracker.Snapshot incomingLossStats;
        private final LossTracker.Snapshot outgoingLossStats;

        public Snapshot(double rtt, LossTracker.Snapshot incomingLossStats, LossTracker.Snapshot outgoingLossStats)
        {
            this.rtt = rtt;
            this.incomingLossStats = incomingLossStats;
            this.outgoingLossStats = outgoingLossStats;
        }

        public double getRtt()
        {
            return rtt;
        }

        public LossTracker.Snapshot getIncomingLossStats()
        {
            return incomingLossStats;
        }

        public LossTracker.Snapshot getOutgoingLossStats()
        {
            return outgoingLossStats;
        }

        public ObjectNode toJson()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("rtt", rtt);
            node.set("incoming_loss_stats", incomingLossStats.toJson());
            node.set("outgoing_loss_stats", outgoingLossStats.toJson());
            return node;
        }
    }

    /**
     * (Deviation: upstream is a Kotlin {@code data class}; ported with explicit {@code equals}/{@code hashCode}
     * since it is used as a {@link Map} key.)
     */
    private static final class SsrcAndTimestamp
    {
        private final long ssrc;
        private final long timestamp;

        private SsrcAndTimestamp(long ssrc, long timestamp)
        {
            this.ssrc = ssrc;
            this.timestamp = timestamp;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof SsrcAndTimestamp))
            {
                return false;
            }
            SsrcAndTimestamp other = (SsrcAndTimestamp) o;
            return ssrc == other.ssrc && timestamp == other.timestamp;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(ssrc, timestamp);
        }
    }
}
