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
package org.jitsi.nlj.rtp;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator;
import org.jitsi.nlj.util.ArrayCache;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.nlj.util.RtpSequenceIndexTracker;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.PacketReport;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.UnreceivedPacketReport;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.RangedStringKt;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Implements transport-cc functionality.
 *
 * See https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
 *
 * @author Boris Grozev
 * @author Julian Chukwu
 * @author George Politis
 */
public class ClassicTransportCcEngine extends TransportCcEngine
{
    /**
     * The maximum number of received packets and their timestamps to save.
     *
     * NOTE rtt + minimum amount
     * XXX this is an uninformed value.
     */
    private static final int MAX_OUTGOING_PACKETS_HISTORY = 1000;

    private final BandwidthEstimator bandwidthEstimator;
    private final Clock clock;

    /**
     * The {@link Logger} used by this instance for logging output.
     */
    private final Logger logger;

    public final LongAdder numPacketsReported = new LongAdder();
    public final LongAdder numPacketsReportedLost = new LongAdder();
    public final LongAdder numDuplicateReports = new LongAdder();
    public final LongAdder numPacketsReportedAfterLost = new LongAdder();
    public final LongAdder numPacketsUnreported = new LongAdder();
    public final LongAdder numMissingPacketReports = new LongAdder();

    /**
     * The reference time of the remote clock. This is used to rebase the
     * arrival times in the TCC packets to a meaningful time base (that of the
     * sender). This is technically not necessary and it's done for convenience.
     */
    private Instant remoteReferenceTime = InstantKt.NEVER;

    /**
     * Local time to map to the reference time of the remote clock. This is used
     * to rebase the arrival times in the TCC packets to a meaningful time base
     * (that of the sender). This is technically not necessary and it's done for
     * convenience.
     */
    private Instant localReferenceTime = InstantKt.NEVER;

    /**
     * Holds a key value pair of the packet sequence number and an object made
     * up of the packet send time and the packet size.
     */
    private final PacketDetailTracker sentPacketDetails;

    private final List<Integer> missingPacketDetailSeqNums = new ArrayList<>();

    private Duration lastRtt = null;

    public ClassicTransportCcEngine(BandwidthEstimator bandwidthEstimator, Logger parentLogger)
    {
        this(bandwidthEstimator, parentLogger, Clock.systemUTC());
    }

    public ClassicTransportCcEngine(BandwidthEstimator bandwidthEstimator, Logger parentLogger, Clock clock)
    {
        this.bandwidthEstimator = bandwidthEstimator;
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.sentPacketDetails = new PacketDetailTracker(clock);
    }

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    @Override
    public void onRttUpdate(Duration rtt)
    {
        Instant now = clock.instant();
        bandwidthEstimator.onRttUpdate(now, rtt);
        lastRtt = rtt;
    }

    @Override
    public void rtcpPacketReceived(RtcpPacket rtcpPacket, @Nullable Instant receivedTime)
    {
        if (rtcpPacket instanceof RtcpFbTccPacket)
        {
            tccReceived((RtcpFbTccPacket) rtcpPacket);
        }
    }

    private void tccReceived(RtcpFbTccPacket tccPacket)
    {
        Instant now = clock.instant();
        Instant currArrivalTimestamp = tccPacket.BaseTime();
        if (remoteReferenceTime.equals(InstantKt.NEVER))
        {
            remoteReferenceTime = currArrivalTimestamp;
            localReferenceTime = now;
        }

        for (PacketReport packetReport : tccPacket)
        {
            int tccSeqNum = packetReport.getSeqNum();
            PacketDetail packetDetail = sentPacketDetails.get(tccSeqNum);

            if (packetDetail == null)
            {
                if (packetReport instanceof ReceivedPacketReport)
                {
                    currArrivalTimestamp = currArrivalTimestamp.plus(((ReceivedPacketReport) packetReport).getDeltaDuration());
                    missingPacketDetailSeqNums.add(tccSeqNum);
                    numMissingPacketReports.increment();
                }
                continue;
            }

            if (packetReport instanceof UnreceivedPacketReport)
            {
                if (packetDetail.state == PacketDetailState.Unreported)
                {
                    bandwidthEstimator.processPacketLoss(now, packetDetail.packetSendTime, tccSeqNum);
                    packetDetail.state = PacketDetailState.ReportedLost;
                    numPacketsReported.increment();
                    numPacketsReportedLost.increment();
                    synchronized (this)
                    {
                        for (LossListener listener : lossListeners)
                        {
                            listener.packetLost(1);
                        }
                    }
                }
            }
            else if (packetReport instanceof ReceivedPacketReport)
            {
                currArrivalTimestamp = currArrivalTimestamp.plus(((ReceivedPacketReport) packetReport).getDeltaDuration());

                if (packetDetail.state == PacketDetailState.Unreported
                    || packetDetail.state == PacketDetailState.ReportedLost)
                {
                    boolean previouslyReportedLost = packetDetail.state == PacketDetailState.ReportedLost;
                    if (previouslyReportedLost)
                    {
                        numPacketsReportedAfterLost.increment();
                        numPacketsReportedLost.decrement();
                        /* Packet has already been counted in numPacketsReported */
                    }
                    else
                    {
                        numPacketsReported.increment();
                    }

                    Instant arrivalTimeInLocalClock =
                        currArrivalTimestamp.minus(Duration.between(localReferenceTime, remoteReferenceTime));

                    bandwidthEstimator.processPacketArrival(
                        now,
                        packetDetail.packetSendTime,
                        arrivalTimeInLocalClock,
                        tccSeqNum,
                        packetDetail.packetLength,
                        (byte) 0,
                        previouslyReportedLost
                    );
                    synchronized (this)
                    {
                        for (LossListener listener : lossListeners)
                        {
                            listener.packetReceived(previouslyReportedLost);
                        }
                    }
                    packetDetail.state = PacketDetailState.ReportedReceived;
                }
                else if (packetDetail.state == PacketDetailState.ReportedReceived)
                {
                    numDuplicateReports.increment();
                }
            }
        }
        bandwidthEstimator.feedbackComplete(now);

        if (!missingPacketDetailSeqNums.isEmpty())
        {
            List<Integer> receivedSeqNums = new ArrayList<>();
            for (PacketReport packetReport : tccPacket)
            {
                if (packetReport instanceof ReceivedPacketReport)
                {
                    receivedSeqNums.add(packetReport.getSeqNum());
                }
            }
            String sizeDescription = sentPacketDetails.isEmpty()
                ? "Sent packet details map was empty."
                : "Latest seqNum was " + sentPacketDetails.getLastSequence() + ", size is "
                    + sentPacketDetails.getSize() + ".";
            String rttDescription = lastRtt != null ? " Latest RTT is " + DurationKt.formatMilli(lastRtt) + " ms." : "";
            logger.warn(
                "TCC packet contained received sequence numbers: " +
                    RangedStringKt.joinToRangedString(receivedSeqNums, ", ", "-", "", "", -1, "...") + ". " +
                    "Couldn't find packet detail for the seq nums: " +
                    RangedStringKt.joinToRangedString(missingPacketDetailSeqNums, ", ", "-", "", "", -1, "...") +
                    ". " + sizeDescription + rttDescription
            );
            missingPacketDetailSeqNums.clear();
        }
    }

    @Override
    public void mediaPacketTagged(PacketInfo packetInfo, long tccSeqNum)
    {
        /* Nothing needs to be done */
    }

    @Override
    public void mediaPacketSent(PacketInfo packetInfo, long tccSeqNum)
    {
        Instant now = clock.instant();
        int seq = (int) (tccSeqNum & 0xFFFF);
        if (!sentPacketDetails.insert(seq, new PacketDetail(DataSize.ofBytes(packetInfo.getPacket().getLength()), now)))
        {
            /* Very old seq? Something odd is happening with whatever is
             * generating tccSeqNum values.
             */
            logger.warn(
                "Not inserting very old TCC seq num " + seq + " (" + tccSeqNum + "), latest is " +
                    sentPacketDetails.getLastSequence() + ", size is " + sentPacketDetails.getSize()
            );
        }
    }

    @Override
    public StatisticsSnapshot getStatistics()
    {
        return new StatisticsSnapshot(
            numPacketsReported.sum(),
            numPacketsReportedLost.sum(),
            numDuplicateReports.sum(),
            numPacketsReportedAfterLost.sum(),
            numPacketsUnreported.sum(),
            numMissingPacketReports.sum(),
            bandwidthEstimator.getStats()
        );
    }

    @Override
    public void addBandwidthListener(BandwidthListener listener)
    {
        bandwidthEstimator.addListener(listener);
    }

    @Override
    public void removeBandwidthListener(BandwidthListener listener)
    {
        bandwidthEstimator.removeListener(listener);
    }

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
    }

    /**
     * {@link PacketDetailState} is the state of a {@link PacketDetail}
     */
    private enum PacketDetailState
    {
        Unreported,
        ReportedLost,
        ReportedReceived
    }

    /**
     * {@link PacketDetail} is an object that holds the
     * length(size) of the packet in {@code packetLength}
     * and the time stamps of the outgoing packet
     * in {@code packetSendTime}
     */
    private static class PacketDetail
    {
        final DataSize packetLength;
        final Instant packetSendTime;

        /**
         * {@code state} represents the state of this packet detail with regards to the
         * reception of a TCC feedback from the remote side.  {@link PacketDetail}s start out
         * as {@code Unreported}: once we receive a TCC feedback from the remote side referring
         * to this packet, the state will transition to either {@code ReportedLost} or
         * {@code ReportedReceived}.
         */
        PacketDetailState state = PacketDetailState.Unreported;

        PacketDetail(DataSize packetLength, Instant packetSendTime)
        {
            this.packetLength = packetLength;
            this.packetSendTime = packetSendTime;
        }
    }

    public static class StatisticsSnapshot extends TransportCcEngine.StatisticsSnapshot
    {
        public final long numPacketsReported;
        public final long numPacketsReportedLost;
        public final long numDuplicateReports;
        public final long numPacketsReportedAfterLost;
        public final long numPacketsUnreported;
        public final long numMissingPacketReports;
        public final BandwidthEstimator.StatisticsSnapshot bandwidthEstimatorStats;

        public StatisticsSnapshot(
            long numPacketsReported,
            long numPacketsReportedLost,
            long numDuplicateReports,
            long numPacketsReportedAfterLost,
            long numPacketsUnreported,
            long numMissingPacketReports,
            BandwidthEstimator.StatisticsSnapshot bandwidthEstimatorStats)
        {
            this.numPacketsReported = numPacketsReported;
            this.numPacketsReportedLost = numPacketsReportedLost;
            this.numDuplicateReports = numDuplicateReports;
            this.numPacketsReportedAfterLost = numPacketsReportedAfterLost;
            this.numPacketsUnreported = numPacketsUnreported;
            this.numMissingPacketReports = numMissingPacketReports;
            this.bandwidthEstimatorStats = bandwidthEstimatorStats;
        }

        @Override
        public ObjectNode toJson()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", ClassicTransportCcEngine.class.getSimpleName());
            node.put("numPacketsReported", numPacketsReported);
            node.put("numPacketsReportedLost", numPacketsReportedLost);
            node.put("numDuplicateReports", numDuplicateReports);
            node.put("numPacketsReportedAfterLost", numPacketsReportedAfterLost);
            node.put("numPacketsUnreported", numPacketsUnreported);
            node.put("numMissingPacketReports", numMissingPacketReports);
            node.set("bandwidth_estimator_stats", bandwidthEstimatorStats.toJson());
            return node;
        }
    }

    private class PacketDetailTracker extends ArrayCache<PacketDetail>
    {
        private final RtpSequenceIndexTracker rtpSequenceIndexTracker = new RtpSequenceIndexTracker();

        PacketDetailTracker(Clock clock)
        {
            // We don't want to clone [PacketDetail] objects that get put in the tracker.
            super(MAX_OUTGOING_PACKETS_HISTORY, item -> item, true, clock);
        }

        @Override
        protected void discardItem(PacketDetail item)
        {
            if (item.state == PacketDetailState.Unreported)
            {
                numPacketsUnreported.increment();
            }
        }

        /**
         * Gets a packet with a given RTP sequence number from the cache.
         */
        PacketDetail get(int sequenceNumber)
        {
            // Note that we use [interpret] because we don't want the ROC to get out of sync because of funny
            // requests (TCCs)
            long index = rtpSequenceIndexTracker.interpret(sequenceNumber);
            ArrayCache.Container<PacketDetail> container = super.getContainer(index, true);
            return container != null ? container.item : null;
        }

        boolean insert(int seq, PacketDetail packetDetail)
        {
            long index = rtpSequenceIndexTracker.update(seq);
            return super.insertItem(packetDetail, index, packetDetail.packetSendTime.toEpochMilli());
        }

        int getLastSequence()
        {
            long lastIndex = super.getLastIndex();
            return lastIndex == -1 ? -1 : (int) (lastIndex & 0xFFFF);
        }
    }
}
