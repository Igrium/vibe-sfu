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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.format.PayloadType;
import org.jitsi.nlj.format.RtxPayloadType;
import org.jitsi.nlj.stats.JitterStats;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Track various statistics about received RTP streams to be used in SR/RR report blocks
 */
public class IncomingStatisticsTracker extends ObserverNode
{
    private final ReadOnlyStreamInformationStore streamInformationStore;
    private final Map<Long, IncomingSsrcStats> ssrcStats = new ConcurrentHashMap<>();

    public IncomingStatisticsTracker(ReadOnlyStreamInformationStore streamInformationStore)
    {
        super("Incoming statistics tracker");
        this.streamInformationStore = streamInformationStore;
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();
        PayloadType payloadType = streamInformationStore.getRtpPayloadTypes().get((byte) rtpPacket.getPayloadType());
        if (payloadType != null)
        {
            // We don't want to track jitter, etc. for RTX streams
            if (!(payloadType instanceof RtxPayloadType))
            {
                IncomingSsrcStats stats = ssrcStats.computeIfAbsent(
                    rtpPacket.getSsrc(),
                    k -> new IncomingSsrcStats(rtpPacket.getSsrc(), rtpPacket.getSequenceNumber(), payloadType.getMediaType())
                );
                Instant packetSentTimestamp = RtpUtils.convertRtpTimestampToInstant(
                    (int) rtpPacket.getTimestamp(),
                    payloadType.getClockRate()
                );
                Instant receivedTime = packetInfo.getReceivedTime();
                if (receivedTime != null)
                {
                    stats.packetReceived(rtpPacket, packetSentTimestamp, receivedTime);
                }
            }
        }
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        IncomingStatisticsSnapshot stats = getSnapshot();
        stats.getSsrcStats().forEach((ssrc, streamStats) -> block.addJson(ssrc.toString(), streamStats.toJson()));
        return block;
    }

    /**
     * Don't aggregate the per-SSRC stats.
     */
    @Override
    protected NodeStatsBlock getNodeStatsToAggregate()
    {
        return super.getNodeStats();
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    /**
     * Gets a snapshot of the SSRCs which have received a packet since the last call to this method. There is a
     * single flag keeping track of activity, so this should not be used in more than one place. Currently, it is
     * used for RR generation. Other code should use {@link #getSnapshot()}.
     */
    public IncomingStatisticsSnapshot getSnapshotOfActiveSsrcs()
    {
        Map<Long, IncomingSsrcStats.Snapshot> result = new HashMap<>();
        ssrcStats.forEach((ssrc, stats) -> {
            IncomingSsrcStats.Snapshot snapshot = stats.getSnapshotIfActive();
            if (snapshot != null)
            {
                result.put(ssrc, snapshot);
            }
        });
        return new IncomingStatisticsSnapshot(result);
    }

    public IncomingStatisticsSnapshot getSnapshot()
    {
        Map<Long, IncomingSsrcStats.Snapshot> result = new HashMap<>();
        ssrcStats.forEach((ssrc, stats) -> result.put(ssrc, stats.getSnapshot()));
        return new IncomingStatisticsSnapshot(result);
    }

    public static class IncomingStatisticsSnapshot
    {
        /**
         * Per-ssrc stats.
         */
        private final Map<Long, IncomingSsrcStats.Snapshot> ssrcStats;

        public IncomingStatisticsSnapshot(Map<Long, IncomingSsrcStats.Snapshot> ssrcStats)
        {
            this.ssrcStats = ssrcStats;
        }

        public Map<Long, IncomingSsrcStats.Snapshot> getSsrcStats()
        {
            return ssrcStats;
        }

        public ObjectNode toJson()
        {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            ssrcStats.forEach((ssrc, snapshot) -> o.set(ssrc.toString(), snapshot.toJson()));
            return o;
        }
    }

    /**
     * Tracks various statistics for the stream using ssrc {@code ssrc}.  Some statistics are tracked only
     * over a specific interval (in between calls to reset) and others persist across calls to reset.  This
     * is tuned for use in generating an RR packet.
     * TODO: max dropout/max misorder/probation handling according to appendix A.1
     */
    public static class IncomingSsrcStats
    {
        /**
         * The maximum 'out-of-order' amount, meaning a packet whose sequence number
         * difference from the current highest sequence number larger than this amount
         * will not be treated as a received out-of-order packet (and therefore subtract
         * from the cumulative loss amount)
         */
        public static final int MAX_OOO_AMOUNT = 100;

        /**
         * https://tools.ietf.org/html/rfc3550#appendix-A.1
         * "...a source is declared valid only after MIN_SEQUENTIAL packets have been received in
         * sequence."
         */
        public static final int INITIAL_MIN_SEQUENTIAL = 2;

        public static final int MAX_DROPOUT = 3000;

        /**
         * The maximum delay between two packets that counts as activity (as opposed to the stream going inactive
         * and back to active).
         */
        public static final Duration ACTIVITY_TIMEOUT = Duration.ofMillis(1000);

        /**
         * Find how many packets we would expected to have received in the range [{@code baseSeqNum},
         * {@code currSeqNum}], taking into account the amount of cycles present when we received {@code baseSeqNum}
         * ({@code baseSeqNumCycles}) and the amount of cycles when we received {@code currSeqNum}
         * ({@code currCycles})
         */
        public static int calculateExpectedPacketCount(
            int baseSeqNumCycles,
            int baseSeqNum,
            int currCycles,
            int currSeqNum)
        {
            int baseExtended = (baseSeqNumCycles << 16) + baseSeqNum;
            int maxExtended = (currCycles << 16) + currSeqNum;

            return maxExtended - baseExtended + 1;
        }

        private final long ssrc;
        private int baseSeqNum;
        private final MediaType mediaType;

        // TODO: for now we'll synchronize access to all the stats so we can create a consistent snapshot when it's
        // requested from another context.
        private final Object statsLock = new Object();
        // Start variables protected by statsLock
        /**
         * This will be initialized to the first sequence number we process
         */
        private int maxSeqNum;
        private int seqNumCycles = 0;
        private int cumulativePacketsLost = 0;
        private int outOfOrderPacketCount = 0;
        private final JitterStats jitterStats = new JitterStats();
        private int numReceivedPackets = 0;
        private int numReceivedBytes = 0;

        /** How long this SSRC has been active. */
        private Duration durationActive = Duration.ZERO;

        /** The receiveTime of the last packet */
        private Instant lastPacketReceivedTime;

        /**
         * Whether there has been any activity (packets received) since the last time the stats were queried with
         * onlyActive = true.
         */
        private boolean activitySinceLastSnapshot = false;
        // End variables protected by statsLock

        private int probation = INITIAL_MIN_SEQUENTIAL;

        public IncomingSsrcStats(long ssrc, int baseSeqNum, MediaType mediaType)
        {
            this.ssrc = ssrc;
            this.baseSeqNum = baseSeqNum;
            this.mediaType = mediaType;
            this.maxSeqNum = baseSeqNum;
        }

        private int getNumExpectedPackets()
        {
            return calculateExpectedPacketCount(0, baseSeqNum, seqNumCycles, maxSeqNum);
        }

        private Snapshot createSnapshot()
        {
            return new Snapshot(
                numReceivedPackets,
                numReceivedBytes,
                maxSeqNum,
                seqNumCycles,
                getNumExpectedPackets(),
                cumulativePacketsLost,
                jitterStats.getJitter(),
                durationActive,
                mediaType
            );
        }

        public Snapshot getSnapshotIfActive()
        {
            synchronized (statsLock)
            {
                if (!activitySinceLastSnapshot)
                {
                    return null;
                }
                activitySinceLastSnapshot = false;
                return createSnapshot();
            }
        }

        public Snapshot getSnapshot()
        {
            synchronized (statsLock)
            {
                return createSnapshot();
            }
        }

        /**
         * Notify this {@link IncomingSsrcStats} instance that an RTP packet {@code packet} for the stream it is
         * tracking has been received and that it: was sent at {@code packetSentTimestamp} (note this is NOT the
         * raw RTP timestamp, but the 'translated' timestamp which is a function of the RTP timestamp and the
         * clockrate) and was received at {@code packetReceivedTime}
         */
        public void packetReceived(RtpPacket packet, Instant packetSentTimestamp, Instant packetReceivedTime)
        {
            int packetSequenceNumber = packet.getSequenceNumber();
            synchronized (statsLock)
            {
                activitySinceLastSnapshot = true;
                numReceivedPackets++;
                numReceivedBytes += packet.getLength();
                if (lastPacketReceivedTime != null)
                {
                    Duration timeSincePreviousPacket = Duration.between(lastPacketReceivedTime, packetReceivedTime);
                    if (timeSincePreviousPacket.compareTo(ACTIVITY_TIMEOUT) < 0)
                    {
                        durationActive = durationActive.plus(timeSincePreviousPacket);
                    }
                }
                lastPacketReceivedTime = packetReceivedTime;

                if (RtpUtils.isNewerThan(packetSequenceNumber, maxSeqNum))
                {
                    if (RtpUtils.isNextAfter(packetSequenceNumber, maxSeqNum))
                    {
                        if (probation > 0)
                        {
                            probation--;
                            // TODO: do we want to 'reset' the sequence after the probation period is finished?
                        }
                    }
                    else if (inRange(RtpUtils.numPacketsTo(maxSeqNum, packetSequenceNumber), 0, MAX_DROPOUT))
                    {
                        // In order with a gap, but gap is within acceptable range
                        cumulativePacketsLost += RtpUtils.numPacketsTo(maxSeqNum, packetSequenceNumber);
                        maybeResetProbation();
                    }
                    else
                    {
                        // Very large jump
                        // TODO
                    }
                    if (RtpUtils.rolledOverTo(maxSeqNum, packetSequenceNumber))
                    {
                        seqNumCycles++;
                    }
                    maxSeqNum = packetSequenceNumber;
                }
                else
                {
                    // Older packet
                    if (inRange(RtpUtils.numPacketsTo(packetSequenceNumber, maxSeqNum), 0, MAX_OOO_AMOUNT))
                    {
                        // This packet would've already been counted as lost, so subtract from the loss count
                        // NOTE: this is susceptible to inaccuracy in the case of duplicate, out-of-order packets
                        // but for now we'll assume those are rare enough not to cause issues.
                        cumulativePacketsLost--;
                    }
                    else
                    {
                        // Older packet which is too old to be counted as out-of-order
                        // TODO
                    }
                    outOfOrderPacketCount++;
                    maybeResetProbation();
                }

                jitterStats.addPacket(packetSentTimestamp, packetReceivedTime);
            }
        }

        private static boolean inRange(int value, int lo, int hi)
        {
            return value >= lo && value <= hi;
        }

        /**
         * Reset the probation period if it's currently active
         */
        private void maybeResetProbation()
        {
            if (probation > 0)
            {
                // When we reset, we subtract 1 since we've already started receiving packets so we already have
                // a 'starting point' for our sequential check (unlike when this instance is initialized, when we
                // haven't processing any incoming packets yet)
                probation = INITIAL_MIN_SEQUENTIAL - 1;
            }
        }

        /**
         * A class to export a consistent snapshot of the data held inside {@link IncomingSsrcStats}
         * TODO: these really need to be documented!
         */
        public static class Snapshot
        {
            private final int numReceivedPackets;
            private final int numReceivedBytes;
            private final int maxSeqNum;
            private final int seqNumCycles;
            private final int numExpectedPackets;
            private final int cumulativePacketsLost;
            private final double jitter;
            private final Duration durationActive;
            private final MediaType mediaType;

            public Snapshot(
                int numReceivedPackets,
                int numReceivedBytes,
                int maxSeqNum,
                int seqNumCycles,
                int numExpectedPackets,
                int cumulativePacketsLost,
                double jitter,
                Duration durationActive,
                MediaType mediaType)
            {
                this.numReceivedPackets = numReceivedPackets;
                this.numReceivedBytes = numReceivedBytes;
                this.maxSeqNum = maxSeqNum;
                this.seqNumCycles = seqNumCycles;
                this.numExpectedPackets = numExpectedPackets;
                this.cumulativePacketsLost = cumulativePacketsLost;
                this.jitter = jitter;
                this.durationActive = durationActive;
                this.mediaType = mediaType;
            }

            public int getNumReceivedPackets()
            {
                return numReceivedPackets;
            }

            public int getNumReceivedBytes()
            {
                return numReceivedBytes;
            }

            public int getMaxSeqNum()
            {
                return maxSeqNum;
            }

            public int getSeqNumCycles()
            {
                return seqNumCycles;
            }

            public int getNumExpectedPackets()
            {
                return numExpectedPackets;
            }

            public int getCumulativePacketsLost()
            {
                return cumulativePacketsLost;
            }

            public double getJitter()
            {
                return jitter;
            }

            public Duration getDurationActive()
            {
                return durationActive;
            }

            public MediaType getMediaType()
            {
                return mediaType;
            }

            public int computeFractionLost(Snapshot previousSnapshot)
            {
                int numExpectedPacketsInterval = numExpectedPackets - previousSnapshot.numExpectedPackets;
                int numReceivedPacketsInterval = numReceivedPackets - previousSnapshot.numReceivedPackets;

                int numLostPacketsInterval = numExpectedPacketsInterval - numReceivedPacketsInterval;
                if (numExpectedPacketsInterval == 0 || numLostPacketsInterval <= 0)
                {
                    return 0;
                }
                else
                {
                    return (int) (((long) numLostPacketsInterval << 8) / (double) numExpectedPacketsInterval);
                }
            }

            public ObjectNode toJson()
            {
                ObjectNode o = JsonNodeFactory.instance.objectNode();
                o.put("num_received_packets", numReceivedPackets);
                o.put("num_received_bytes", numReceivedBytes);
                o.put("max_seq_num", maxSeqNum);
                o.put("seq_num_cycles", seqNumCycles);
                o.put("num_expected_packets", numExpectedPackets);
                o.put("cumulative_packets_lost", cumulativePacketsLost);
                o.put("jitter", jitter);
                o.put("duration_active_ms", durationActive.toMillis());
                o.put("media_type", mediaType.toString());
                return o;
            }
        }
    }
}
