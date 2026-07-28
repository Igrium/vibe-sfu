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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.PacketOrigin;
import org.jitsi.nlj.rtp.AudioRtpPacket;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.nlj.transform.node.incoming.BitrateCalculator;
import org.jitsi.nlj.util.BitrateTracker;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class OutgoingStatisticsTracker extends ObserverNode
{
    private static final TimeSeriesLogger timeseriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(OutgoingStatisticsTracker.class);

    private final DiagnosticContext diagnosticContext;

    /**
     * Per-SSRC statistics
     */
    private final Map<Long, OutgoingSsrcStats> ssrcStats = new ConcurrentHashMap<>();

    private int numAudioPackets = 0;
    private int numVideoPackets = 0;

    private final BitrateTracker videoBitrate = BitrateCalculator.createBitrateTracker();

    private final Map<PacketOrigin, BitrateTracker> videoBitratesByOrigin = new HashMap<>();

    public OutgoingStatisticsTracker(DiagnosticContext diagnosticContext)
    {
        super("Outgoing statistics tracker");
        this.diagnosticContext = diagnosticContext;
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();

        if (rtpPacket instanceof AudioRtpPacket)
        {
            numAudioPackets++;
        }
        else if (rtpPacket instanceof VideoRtpPacket)
        {
            numVideoPackets++;

            /* These bitrate measurements are only used in the timeseries log below, so only calculate them
             * if the timeseries is enabled. */
            if (timeseriesLogger.isTraceEnabled())
            {
                videoBitrate.update(DataSize.ofBytes(rtpPacket.getLength()));
                videoBitratesByOrigin.computeIfAbsent(
                    packetInfo.getPacketOrigin(),
                    origin -> BitrateCalculator.createBitrateTracker()
                ).update(DataSize.ofBytes(rtpPacket.getLength()));
            }
        }

        OutgoingSsrcStats stats = ssrcStats.computeIfAbsent(rtpPacket.getSsrc(), OutgoingSsrcStats::new);
        stats.packetSent(rtpPacket.getLength(), rtpPacket.getTimestamp());
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        OutgoingStatisticsSnapshot stats = getSnapshot();
        stats.getSsrcStats().forEach((ssrc, streamStats) -> block.addJson(ssrc.toString(), streamStats.toJson()));
        return block;
    }

    /**
     * Don't aggregate the per-SSRC stats.
     */
    @Override
    protected NodeStatsBlock getNodeStatsToAggregate()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("num_audio_packets", numAudioPackets);
        block.addNumber("num_video_packets", numVideoPackets);
        return block;
    }

    @Override
    protected void trace(Runnable f)
    {
        f.run();
    }

    public OutgoingStatisticsSnapshot getSnapshot()
    {
        Map<Long, OutgoingSsrcStats.Snapshot> snapshotMap = new HashMap<>();
        ssrcStats.forEach((ssrc, stats) -> snapshotMap.put(ssrc, stats.getSnapshot()));
        OutgoingStatisticsSnapshot snapshot = new OutgoingStatisticsSnapshot(snapshotMap);

        if (timeseriesLogger.isTraceEnabled())
        {
            DiagnosticContext.TimeSeriesPoint point = diagnosticContext.makeTimeSeriesPoint("sent_video_stream_stats")
                .addField("bitrate_bps", videoBitrate.getRate().getBps());
            videoBitratesByOrigin.forEach(
                (origin, tracker) -> point.addField("video_" + origin + "_bitrate", tracker.getRate().getBps())
            );

            timeseriesLogger.trace(point);
        }
        return snapshot;
    }

    public OutgoingSsrcStats.Snapshot getSsrcSnapshot(long ssrc)
    {
        OutgoingSsrcStats stats = ssrcStats.get(ssrc);
        return stats != null ? stats.getSnapshot() : null;
    }

    public static class OutgoingStatisticsSnapshot
    {
        /**
         * Per-ssrc stats.
         */
        private final Map<Long, OutgoingSsrcStats.Snapshot> ssrcStats;

        public OutgoingStatisticsSnapshot(Map<Long, OutgoingSsrcStats.Snapshot> ssrcStats)
        {
            this.ssrcStats = ssrcStats;
        }

        public Map<Long, OutgoingSsrcStats.Snapshot> getSsrcStats()
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

    public static class OutgoingSsrcStats
    {
        private final long ssrc;

        private final Object statsLock = new Object();

        // Start variables protected by statsLock
        private int packetCount = 0;
        private int octetCount = 0;
        private long mostRecentRtpTimestamp = 0;
        // End variables protected by statsLock

        public OutgoingSsrcStats(long ssrc)
        {
            this.ssrc = ssrc;
        }

        public void packetSent(int packetSizeOctets, long rtpTimestamp)
        {
            synchronized (statsLock)
            {
                packetCount++;
                octetCount += packetSizeOctets;
                mostRecentRtpTimestamp = rtpTimestamp;
            }
        }

        public Snapshot getSnapshot()
        {
            synchronized (statsLock)
            {
                return new Snapshot(packetCount, octetCount, mostRecentRtpTimestamp);
            }
        }

        public static class Snapshot
        {
            private final int packetCount;
            private final int octetCount;
            private final long mostRecentRtpTimestamp;

            public Snapshot(int packetCount, int octetCount, long mostRecentRtpTimestamp)
            {
                this.packetCount = packetCount;
                this.octetCount = octetCount;
                this.mostRecentRtpTimestamp = mostRecentRtpTimestamp;
            }

            public int getPacketCount()
            {
                return packetCount;
            }

            public int getOctetCount()
            {
                return octetCount;
            }

            public long getMostRecentRtpTimestamp()
            {
                return mostRecentRtpTimestamp;
            }

            public ObjectNode toJson()
            {
                ObjectNode o = JsonNodeFactory.instance.objectNode();
                o.put("packet_count", packetCount);
                o.put("octet_count", octetCount);
                o.put("most_recent_rtp_timestamp", mostRecentRtpTimestamp);
                return o;
            }
        }
    }
}
