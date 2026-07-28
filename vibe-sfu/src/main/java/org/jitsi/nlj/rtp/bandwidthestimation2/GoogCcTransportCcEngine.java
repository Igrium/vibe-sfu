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
import org.jitsi.nlj.rtp.LossListener;
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.RtcpReportBlock;
import org.jitsi.rtp.rtcp.RtcpRrPacket;
import org.jitsi.rtp.rtcp.RtcpSrPacket;
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Transport CC engine invoking GoogCc NetworkController.  Contains some code based loosely on
 * WebRTC call/rtp_transport_controller_send.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138)
 *
 */
public class GoogCcTransportCcEngine extends TransportCcEngine
{
    /** Callback invoked to send probing data of the given size. Returns the number of bytes
     * actually sent. (Upstream: {@code sendProbing: (DataSize, Any?) -> Int}.) */
    @FunctionalInterface
    public interface ProbeSender
    {
        int sendProbing(DataSize size, Object probeClusterInfo);
    }

    public final DiagnosticContext diagnosticContext;
    private final ScheduledExecutorService scheduledExecutor;
    private final ProbeSender sendProbing;
    public final Clock clock;

    private final Logger logger;

    private final TransportFeedbackAdapter feedbackAdapter;
    private final NetworkControllerInterface networkController;
    private final BitrateProber bitrateProber;
    private ScheduledFuture<?> probeTask = null;

    private ScheduledFuture<?> processTask = null;

    private final LinkedList<BandwidthListener> listeners = new LinkedList<>();

    public GoogCcTransportCcEngine(
        DiagnosticContext diagnosticContext,
        Logger parentLogger,
        ScheduledExecutorService scheduledExecutor,
        ProbeSender sendProbing,
        Clock clock)
    {
        this.diagnosticContext = diagnosticContext;
        this.scheduledExecutor = scheduledExecutor;
        this.sendProbing = sendProbing;
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(GoogCcTransportCcEngine.class.getName());
        this.feedbackAdapter = new TransportFeedbackAdapter(logger);
        TargetRateConstraints constraints = new TargetRateConstraints();
        constraints.atTime = clock.instant();
        constraints.startingRate = BandwidthEstimatorConfig.initBw;
        constraints.maxDataRate = BandwidthEstimatorConfig.maxBw;
        constraints.minDataRate = BandwidthEstimatorConfig.minBw;
        this.networkController = factory.create(
            new NetworkControllerConfig(
                logger,
                diagnosticContext,
                constraints,
                new StreamsConfig()
            )
        );
        this.bitrateProber = new BitrateProber(logger);
        this.lastReportBlockTime = clock.instant();
    }

    public GoogCcTransportCcEngine(
        DiagnosticContext diagnosticContext,
        Logger parentLogger,
        ScheduledExecutorService scheduledExecutor,
        ProbeSender sendProbing)
    {
        this(diagnosticContext, parentLogger, scheduledExecutor, sendProbing, Clock.systemUTC());
    }

    @Override
    public synchronized void onRttUpdate(Duration rtt)
    {
        NetworkControlUpdate update =
            networkController.onRoundTripTimeUpdate(
                new RoundTripTimeUpdate(clock.instant(), rtt, false));
        processUpdate(update);
    }

    @Override
    public synchronized void rtcpPacketReceived(RtcpPacket rtcpPacket, Instant receivedTime)
    {
        if (rtcpPacket instanceof RtcpFbTccPacket)
        {
            RtcpFbTccPacket tccPacket = (RtcpFbTccPacket) rtcpPacket;
            Instant time = receivedTime != null ? receivedTime : clock.instant();
            TransportPacketsFeedback feedback = feedbackAdapter.processTransportFeedback(tccPacket, time);
            if (feedback != null)
            {
                NetworkControlUpdate update = networkController.onTransportPacketsFeedback(feedback);
                processUpdate(update);

                for (PacketResult fb : feedback.packetFeedbacks)
                {
                    for (LossListener l : lossListeners)
                    {
                        if (fb.isReceived())
                        {
                            l.packetReceived(fb.previouslyReportedLost);
                        }
                        else if (!fb.previouslyReportedLost)
                        {
                            l.packetLost(1);
                        }
                    }
                }
            }
        }
        else if (rtcpPacket instanceof RtcpFbRembPacket)
        {
            /* Ignore REMB packets - if we're supposed to be receiving them they'll be handled by [RembHandler],
             * and if we're not we're getting mysterious spurious REMB messages which we want to ignore.
             */

            /*
            Instant time = receivedTime != null ? receivedTime : clock.instant();
            RemoteBitrateReport msg = new RemoteBitrateReport(time, Bandwidth.ofBps(rtcpPacket.getBitrate()));
            NetworkControlUpdate update = networkController.onRemoteBitrateReport(msg);
            processUpdate(update);
             */
        }
        else if (rtcpPacket instanceof RtcpSrPacket)
        {
            Instant time = receivedTime != null ? receivedTime : clock.instant();
            onReport(time, ((RtcpSrPacket) rtcpPacket).getReportBlocks());
        }
        else if (rtcpPacket instanceof RtcpRrPacket)
        {
            Instant time = receivedTime != null ? receivedTime : clock.instant();
            onReport(time, ((RtcpRrPacket) rtcpPacket).getReportBlocks());
        }
    }

    private static class LossReport
    {
        long extendedHighestSequenceNumber = 0;
        int cumulativeLost = 0;
    }

    private final Map<Long, LossReport> lastReportBlocks = new HashMap<>();
    private Instant lastReportBlockTime;

    private void onReport(Instant receiveTime, List<RtcpReportBlock> reportBlocks)
    {
        if (reportBlocks.isEmpty())
        {
            return;
        }

        long totalPacketsLostDelta = 0L;
        long totalPacketsDelta = 0L;

        for (RtcpReportBlock reportBlock : reportBlocks)
        {
            LossReport newLossReport = new LossReport();
            LossReport lastLossReport = lastReportBlocks.putIfAbsent(reportBlock.getSsrc(), newLossReport);
            if (lastLossReport != null)
            {
                totalPacketsDelta += reportBlock.getExtendedHighestSeqNum() - lastLossReport.extendedHighestSequenceNumber;
                totalPacketsLostDelta += reportBlock.getCumulativePacketsLost() - lastLossReport.cumulativeLost;
            }
            LossReport lossReport = lastLossReport != null ? lastLossReport : newLossReport;
            lossReport.extendedHighestSequenceNumber = reportBlock.getExtendedHighestSeqNum();
            lossReport.cumulativeLost = reportBlock.getCumulativePacketsLost();
        }
        // Can only compute delta if there has been previous blocks to compare to. If
        // not, total_packets_delta will be unchanged and there's nothing more to do.
        if (totalPacketsDelta == 0L)
        {
            return;
        }
        long packetsReceivedDelta = totalPacketsDelta - totalPacketsLostDelta;
        // To detect lost packets, at least one packet has to be received.
        if (packetsReceivedDelta < 1)
        {
            return;
        }
        TransportLossReport msg = new TransportLossReport(
            receiveTime,
            lastReportBlockTime,
            receiveTime,
            totalPacketsLostDelta,
            packetsReceivedDelta
        );
        NetworkControlUpdate update = networkController.onTransportLossReport(msg);
        processUpdate(update);
        lastReportBlockTime = receiveTime;
    }

    @Override
    public synchronized void mediaPacketTagged(PacketInfo packetInfo, long tccSeqNum)
    {
        Instant now = clock.instant();
        DataSize length = DataSize.ofBytes(packetInfo.getPacket().getLength());
        PacedPacketInfo pacedPacketInfo =
            packetInfo.getProbingInfo() instanceof PacedPacketInfo
                ? (PacedPacketInfo) packetInfo.getProbingInfo()
                : null;
        feedbackAdapter.addPacket(
            packetInfo,
            tccSeqNum,
            DataSize.ZERO, // TODO: network overhead
            now
        );
        if (pacedPacketInfo == null)
        {
            bitrateProber.onIncomingPacket(length);
        }
        maybeScheduleProbing(now);
    }

    @Override
    public synchronized void mediaPacketSent(PacketInfo packetInfo, long tccSeqNum)
    {
        Instant now = clock.instant();
        long length = packetInfo.getPacket().getLength();
        SentPacketInfo sentPacketInfo = new SentPacketInfo(
            tccSeqNum,
            now,
            new SentPacketInfo.PacketInfo(
                // TODO I think these should always be true when tccSeqNum is defined?
                true,  // includedInFeedback
                true,  // includedInAllocation
                length
            )
        );
        SentPacket sentPacket = feedbackAdapter.processSentPacket(sentPacketInfo);
        if (sentPacket != null)
        {
            NetworkControlUpdate update = networkController.onSentPacket(sentPacket);
            processUpdate(update);
        }
    }

    @Override
    public synchronized StatisticsSnapshot getStatistics()
    {
        Instant now = clock.instant();
        return new StatisticsSnapshot(
            feedbackAdapter.getStatisitics(),
            ((GoogCcNetworkController) networkController).getStatistics(now)
        );
    }

    @Override
    public synchronized void addBandwidthListener(BandwidthListener listener)
    {
        listeners.add(listener);
    }

    @Override
    public synchronized void removeBandwidthListener(BandwidthListener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public synchronized void start()
    {
        Instant startTime = clock.instant();
        NetworkControlUpdate update =
            networkController.onNetworkAvailability(new NetworkAvailability(startTime, true));
        processUpdate(update); // Does this make sense to do during init?

        update = networkController.onProcessInterval(new ProcessInterval(startTime, null));
        processUpdate(update);

        long processInterval = factory.getProcessInterval().toMillis();

        processTask = scheduledExecutor.scheduleAtFixedRate(() -> {
            synchronized (GoogCcTransportCcEngine.this)
            {
                Instant now = clock.instant();
                NetworkControlUpdate intervalUpdate =
                    networkController.onProcessInterval(new ProcessInterval(now, null));
                processUpdate(intervalUpdate);
            }
        }, processInterval, processInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop()
    {
        // Stop bitrateProber from initiating any new probes
        bitrateProber.setEnabled(false);
        if (probeTask != null)
        {
            probeTask.cancel(false);
        }
        if (processTask != null)
        {
            processTask.cancel(false);
        }
    }

    private void processUpdate(NetworkControlUpdate update)
    {
        if (update.targetRate != null)
        {
            TargetTransferRate targetRate = update.targetRate;
            logger.debug(() -> "GoogleCcEstimator setting TargetRate to " + targetRate);
            for (BandwidthListener it : listeners)
            {
                it.bandwidthEstimationChanged(targetRate.targetRate);
            }
        }
        if (update.congestionWindow != null)
        {
            /* We don't use a congestion window */
            /* TODO: does this do anything bad to the estimator? */
            logger.trace(() -> "GoogleCcEstimator wants to set CongestionWindow to " + update.congestionWindow);
        }
        if (update.pacerConfig != null)
        {
            /* We don't use a pacer */
            /* TODO: does this do anything bad to the estimator? */
            logger.trace(() -> "GoogleCcEstimator wants to set PacerConfig to " + update.pacerConfig);
        }
        List<ProbeClusterConfig> configs = update.probeClusterConfigs;
        if (!configs.isEmpty())
        {
            logger.debug(() -> "GoogleCcEstimator creating " + configs.size() + " ProbeClusterConfigs: " + configs);
            for (ProbeClusterConfig config : configs)
            {
                bitrateProber.createProbeCluster(config);
            }
            maybeScheduleProbing(clock.instant());
        }

        if (timeSeriesLogger.isTraceEnabled() && update.isNotEmpty())
        {
            Instant now = update.getAtTime() != null ? update.getAtTime() : clock.instant();
            GoogCcNetworkController.StatisticsSnapshot stats =
                ((GoogCcNetworkController) networkController).getStatistics(now);
            DiagnosticContext.TimeSeriesPoint statsPoint = diagnosticContext.makeTimeSeriesPoint("goog_cc_stats", now);
            stats.addToTimeSeriesPoint(statsPoint);
            timeSeriesLogger.trace(statsPoint);

            DiagnosticContext.TimeSeriesPoint updatePoint = diagnosticContext.makeTimeSeriesPoint("goog_cc_update", now);
            update.addToTimeSeriesPoint(updatePoint);
            timeSeriesLogger.trace(updatePoint);
        }
    }

    /** Schedule bitrate probing if needed and not current scheduled.
     *  Should be synchronized on this@GoogCcTransportCcEngine. */
    private void maybeScheduleProbing(Instant now)
    {
        if (bitrateProber.isProbing() && probeTask == null)
        {
            Instant nextProbeTime = bitrateProber.nextProbeTime(now);
            if (nextProbeTime.equals(Instant.MAX))
            {
                return;
            }
            long delay = nextProbeTime.equals(Instant.MIN)
                ? 0
                : Math.max(Duration.between(now, nextProbeTime).toMillis(), 0);

            probeTask = scheduledExecutor.schedule(() -> {
                synchronized (GoogCcTransportCcEngine.this)
                {
                    probeTask = null;
                    Instant scheduleNow = clock.instant();
                    PacedPacketInfo cluster = bitrateProber.currentCluster(scheduleNow);
                    if (cluster == null)
                    {
                        cluster = new PacedPacketInfo();
                    }
                    if (cluster.probeClusterId == PacedPacketInfo.kNotAProbe)
                    {
                        return;
                    }
                    DataSize probeSize = bitrateProber.recommendedMinProbeSize();
                    int probeSent = sendProbing.sendProbing(probeSize, cluster);
                    bitrateProber.probeSent(scheduleNow, DataSize.ofBytes(probeSent));
                    maybeScheduleProbing(scheduleNow);
                }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static final GoogCcNetworkControllerFactory factory = new GoogCcNetworkControllerFactory();

    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(GoogCcTransportCcEngine.class);

    /* Default config settings to use when this version of the Google transport CC estimator engine is used.
     * (Deviation: upstream reads these via jitsi-metaconfig `by config` from
     * jmt.bwe.estimator.GoogleCc2.*; the library ships the upstream reference.conf defaults inline.) */
    public static final Duration defaultRateTrackerWindowSize = Duration.ofSeconds(1);
    public static final Duration defaultRateTrackerBucketSize = Duration.ofMillis(20);
    public static final Duration defaultInitialIgnoreBwePeriod = Duration.ZERO;

    public static class StatisticsSnapshot extends TransportCcEngine.StatisticsSnapshot
    {
        public final TransportFeedbackAdapter.StatisticsSnapshot transportAdapterState;
        public final GoogCcNetworkController.StatisticsSnapshot networkControllerState;

        public StatisticsSnapshot(
            TransportFeedbackAdapter.StatisticsSnapshot transportAdapterState,
            GoogCcNetworkController.StatisticsSnapshot networkControllerState)
        {
            this.transportAdapterState = transportAdapterState;
            this.networkControllerState = networkControllerState;
        }

        @Override
        public ObjectNode toJson()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", GoogCcTransportCcEngine.class.getSimpleName());
            node.set("transport_adapter", transportAdapterState.toJson());
            node.set("network_controller", networkControllerState.toJson());
            return node;
        }
    }
}
