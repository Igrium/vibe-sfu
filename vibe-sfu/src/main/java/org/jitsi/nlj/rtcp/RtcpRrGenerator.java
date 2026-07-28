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
package org.jitsi.nlj.rtcp;

import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker;
import org.jitsi.nlj.transform.node.incoming.IncomingStatisticsTracker.IncomingSsrcStats;
import org.jitsi.nlj.util.ScheduleExecutorServiceExtensions;
import org.jitsi.rtp.rtcp.CompoundRtcpPacket;
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.RtcpReportBlock;
import org.jitsi.rtp.rtcp.RtcpRrPacketBuilder;
import org.jitsi.rtp.rtcp.RtcpSrPacket;
import org.jitsi.utils.MediaType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Retrieves statistics about incoming streams and creates RTCP RR packets.  Since RR packets are created based on
 * time (and not on a number of incoming packets received, etc.) it does not live within the packet pipelines.
 */
public class RtcpRrGenerator implements RtcpListener
{
    /**
     * The interval at which RRs and REMBs will be sent.
     */
    private static final Duration reportingInterval = Duration.ofMillis(500);

    /**
     * The maximum number of report blocks that fit in a single RR packet.
     */
    private static final int MAX_REPORT_BLOCKS_PER_RR = 31;

    private final ScheduledExecutorService backgroundExecutor;
    private final Consumer<RtcpPacket> rtcpSender;
    private final IncomingStatisticsTracker incomingStatisticsTracker;
    private final Clock clock;

    /**
     * A function which supplies additional RTCP packets (such as REMB) to be sent together with RRs.
     */
    private final Supplier<List<RtcpPacket>> additionalPacketSupplier;

    private boolean running = true;

    private final Map<Long, SenderInfo> senderInfos = new ConcurrentHashMap<>();

    public RtcpRrGenerator(
        ScheduledExecutorService backgroundExecutor,
        IncomingStatisticsTracker incomingStatisticsTracker,
        Supplier<List<RtcpPacket>> additionalPacketSupplier)
    {
        this(backgroundExecutor, rtcpPacket -> { }, incomingStatisticsTracker, Clock.systemUTC(),
            additionalPacketSupplier);
    }

    public RtcpRrGenerator(
        ScheduledExecutorService backgroundExecutor,
        Consumer<RtcpPacket> rtcpSender,
        IncomingStatisticsTracker incomingStatisticsTracker,
        Supplier<List<RtcpPacket>> additionalPacketSupplier)
    {
        this(backgroundExecutor, rtcpSender, incomingStatisticsTracker, Clock.systemUTC(), additionalPacketSupplier);
    }

    public RtcpRrGenerator(
        ScheduledExecutorService backgroundExecutor,
        Consumer<RtcpPacket> rtcpSender,
        IncomingStatisticsTracker incomingStatisticsTracker,
        Clock clock,
        Supplier<List<RtcpPacket>> additionalPacketSupplier)
    {
        this.backgroundExecutor = backgroundExecutor;
        this.rtcpSender = rtcpSender;
        this.incomingStatisticsTracker = incomingStatisticsTracker;
        this.clock = clock;
        this.additionalPacketSupplier = additionalPacketSupplier;

        doWork();
    }

    public boolean isRunning()
    {
        return running;
    }

    public void setRunning(boolean running)
    {
        this.running = running;
    }

    @Override
    public void rtcpPacketReceived(RtcpPacket packet, Instant receivedTime)
    {
        if (packet instanceof RtcpSrPacket)
        {
            RtcpSrPacket srPacket = (RtcpSrPacket) packet;
            // Note the time we received an SR so that it can be used when creating RtcpReportBlocks
            // TODO: we have a concurrency issue here: we could be halfway through updating the senderinfo when
            // the doWork context thread runs
            SenderInfo senderInfo = senderInfos.computeIfAbsent(srPacket.getSenderSsrc(), k -> new SenderInfo());
            senderInfo.lastSrCompactedTimestamp = srPacket.getSenderInfo().getCompactedNtpTimestamp();
            senderInfo.lastSrReceivedTime = receivedTime;
        }
    }

    private void doWork()
    {
        if (running)
        {
            IncomingStatisticsTracker.IncomingStatisticsSnapshot streamStats =
                incomingStatisticsTracker.getSnapshotOfActiveSsrcs();
            Instant now = clock.instant();
            List<RtcpReportBlock> reportBlocks = new ArrayList<>();
            for (Map.Entry<Long, IncomingSsrcStats.Snapshot> entry : streamStats.getSsrcStats().entrySet())
            {
                long ssrc = entry.getKey();
                IncomingSsrcStats.Snapshot statsSnapshot = entry.getValue();
                SenderInfo senderInfo = senderInfos.computeIfAbsent(ssrc, k -> new SenderInfo());
                int fractionLost = statsSnapshot.computeFractionLost(senderInfo.statsSnapshot);
                senderInfo.statsSnapshot = statsSnapshot;

                reportBlocks.add(
                    new RtcpReportBlock(
                        ssrc,
                        fractionLost,
                        statsSnapshot.getCumulativePacketsLost(),
                        statsSnapshot.getSeqNumCycles(),
                        statsSnapshot.getMaxSeqNum(),
                        (long) statsSnapshot.getJitter(),
                        senderInfo.lastSrCompactedTimestamp,
                        senderInfo.getDelaySinceLastSr(now)
                    )
                );
            }

            List<RtcpPacket> packets = new ArrayList<>();
            if (!reportBlocks.isEmpty())
            {
                for (int i = 0; i < reportBlocks.size(); i += MAX_REPORT_BLOCKS_PER_RR)
                {
                    List<RtcpReportBlock> chunk =
                        reportBlocks.subList(i, Math.min(i + MAX_REPORT_BLOCKS_PER_RR, reportBlocks.size()));
                    packets.add(new RtcpRrPacketBuilder(new RtcpHeaderBuilder(), new ArrayList<>(chunk)).build());
                }
            }
            packets.addAll(additionalPacketSupplier.get());

            if (packets.size() == 1)
            {
                rtcpSender.accept(packets.get(0));
            }
            else if (packets.size() > 1)
            {
                for (RtcpPacket packet : CompoundRtcpPacket.createWithMtu(packets))
                {
                    rtcpSender.accept(packet);
                }
            }
            ScheduleExecutorServiceExtensions.schedule(backgroundExecutor, this::doWork, reportingInterval);
        }
    }

    /**
     * Information about a sender that is used in the generation of RTCP report blocks.  NOTE that this does NOT
     * correspond to the Sender Info block of an SR
     * TODO: rename to not be confused with Sender Info in SR?
     */
    private static final class SenderInfo
    {
        long lastSrCompactedTimestamp = 0;
        Instant lastSrReceivedTime;
        // The media type doesn't affect RTCP RR/SR generation. Initialize with a dummy value.
        IncomingSsrcStats.Snapshot statsSnapshot =
            new IncomingSsrcStats.Snapshot(0, 0, 0, 0, 0, 0, 0.0, Duration.ZERO, MediaType.VIDEO);

        private boolean hasReceivedSr()
        {
            return lastSrReceivedTime != null;
        }

        long getDelaySinceLastSr(Instant now)
        {
            if (hasReceivedSr())
            {
                // This value is in 1/65536 of a second, so multiplying by 65536 gives us the value
                return Duration.between(lastSrReceivedTime, now).multipliedBy(65536).getSeconds();
            }
            else
            {
                return 0;
            }
        }
    }
}
