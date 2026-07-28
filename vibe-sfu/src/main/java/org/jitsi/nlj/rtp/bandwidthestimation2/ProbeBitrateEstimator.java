/*
 * Copyright @ 2019-present 8x8, Inc
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

import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.TreeMap;

/**
 * Basic implementation to estimate bitrate of probes.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/probe_bitrate_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class ProbeBitrateEstimator
{
    private final DiagnosticContext diagnosticContext;

    // TODO: pass parent logger in so we have log contexts
    private final Logger logger;

    private final TreeMap<Integer, AggregatedCluster> clusters = new TreeMap<>();

    private Bandwidth estimatedDataRate = null;

    public ProbeBitrateEstimator(Logger parentLogger, DiagnosticContext diagnosticContext)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.diagnosticContext = diagnosticContext;
    }

    public Bandwidth handleProbeAndEstimateBitrate(PacketResult packetFeedback)
    {
        int clusterId = packetFeedback.sentPacket.pacingInfo.probeClusterId;
        if (clusterId == PacedPacketInfo.kNotAProbe)
        {
            throw new IllegalStateException("Check failed: clusterId != PacedPacketInfo.kNotAProbe");
        }

        eraseOldClusters(packetFeedback.receiveTime);

        AggregatedCluster cluster = clusters.computeIfAbsent(clusterId, (k) -> new AggregatedCluster());

        if (packetFeedback.sentPacket.sendTime.isBefore(cluster.firstSend))
        {
            cluster.firstSend = packetFeedback.sentPacket.sendTime;
        }
        if (packetFeedback.sentPacket.sendTime.isAfter(cluster.lastSend))
        {
            cluster.lastSend = packetFeedback.sentPacket.sendTime;
            cluster.sizeLastSend = packetFeedback.sentPacket.size;
        }
        if (packetFeedback.receiveTime.isBefore(cluster.firstReceive))
        {
            cluster.firstReceive = packetFeedback.receiveTime;
            cluster.sizeFirstReceive = packetFeedback.sentPacket.size;
        }
        if (packetFeedback.receiveTime.isAfter(cluster.lastReceive))
        {
            cluster.lastReceive = packetFeedback.receiveTime;
        }
        cluster.sizeTotal = cluster.sizeTotal.plus(packetFeedback.sentPacket.size);
        cluster.numProbes += 1;

        if (packetFeedback.sentPacket.pacingInfo.probeClusterMinProbes <= 0)
        {
            throw new IllegalStateException(
                "Check failed: packetFeedback.sentPacket.pacingInfo.probeClusterMinProbes > 0");
        }
        if (packetFeedback.sentPacket.pacingInfo.probeClusterMinBytes <= 0)
        {
            throw new IllegalStateException(
                "Check failed: packetFeedback.sentPacket.pacingInfo.probeClusterMinBytes > 0");
        }

        double minProbes = packetFeedback.sentPacket.pacingInfo.probeClusterMinProbes *
            kMinReceivedProbesRatio;
        DataSize minSize = DataSize.ofBytes(packetFeedback.sentPacket.pacingInfo.probeClusterMinBytes)
            .times(kMinReceivedBytesRatio);
        if (cluster.numProbes < minProbes || cluster.sizeTotal.compareTo(minSize) < 0)
        {
            return null;
        }

        Duration sendInterval = Duration.between(cluster.firstSend, cluster.lastSend);
        Duration receiveInterval = Duration.between(cluster.firstReceive, cluster.lastReceive);
        if (sendInterval.compareTo(Duration.ZERO) <= 0 || sendInterval.compareTo(kMaxProbeInterval) > 0 ||
            receiveInterval.compareTo(Duration.ZERO) <= 0 ||
            receiveInterval.compareTo(kMaxProbeInterval) > 0)
        {
            logger.info(
                "Probing unsuccessful, invalid send/receive interval " +
                    "[cluster id: " + clusterId + "] [sendInterval: " + sendInterval + "] " +
                    "[receive interval: " + receiveInterval + "]"
            );
            return null;
        }
        // Since the `send_interval` does not include the time it takes to actually
        // send the last packet the size of the last sent packet should not be
        // included when calculating the send bitrate.
        if (cluster.sizeTotal.compareTo(cluster.sizeLastSend) < 0)
        {
            throw new IllegalStateException("Check failed: cluster.sizeTotal >= cluster.sizeLastSend");
        }
        DataSize sendSize = cluster.sizeTotal.minus(cluster.sizeLastSend);
        Bandwidth sendRate = sendSize.per(sendInterval);

        // Since the `receive_interval` does not include the time it takes to
        // actually receive the first packet the size of the first received packet
        // should not be included when calculating the receive bitrate.
        if (cluster.sizeTotal.compareTo(cluster.sizeFirstReceive) < 0)
        {
            throw new IllegalStateException("Check failed: cluster.sizeTotal >= cluster.sizeFirstReceive");
        }
        DataSize receiveSize = cluster.sizeTotal.minus(cluster.sizeFirstReceive);
        Bandwidth receiveRate = receiveSize.per(receiveInterval);

        double ratio = receiveRate.div(sendRate);
        if (ratio > kMaxValidRatio)
        {
            logger.info(
                "Probing unsuccessful, receive/send ratio too high " +
                    "[cluster id: " + clusterId + "] [send: " + sendSize + "/" + sendInterval +
                    " = " + sendRate + "] " +
                    "[receive: " + receiveSize + "/" + receiveInterval + " = " + receiveRate + "] " +
                    "[ratio: " + receiveRate + " / " + sendRate + " = " + ratio +
                    " > kMaxValidRatio (" + kMaxValidRatio + ")]"
            );
            return null;
        }
        logger.info(
            "Probing successful [cluster id: " + clusterId + "] " +
                "[send: " + sendSize + " / " + sendInterval + " = " + sendRate + "]" +
                "[receive: " + receiveSize + " / " + receiveInterval + " = " + receiveRate + "]"
        );

        Bandwidth res = Bandwidth.min(sendRate, receiveRate);
        // If we're receiving at significantly lower bitrate than we were sending at,
        // it suggests that we've found the true capacity of the link. In this case,
        // set the target bitrate slightly lower to not immediately overuse.
        if (receiveRate.compareTo(sendRate.times(kMinRatioForUnsaturatedLink)) < 0)
        {
            if (sendRate.compareTo(receiveRate) <= 0)
            {
                throw new IllegalStateException("Check failed: sendRate > receiveRate");
            }
            res = receiveRate.times(kTargetUtilizationFraction);
        }
        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(
                diagnosticContext.makeTimeSeriesPoint("probe_result_success")
                    .addField("id", clusterId)
                    .addField("bitrate_bps", res.getBps())
            );
        }
        estimatedDataRate = res;
        return estimatedDataRate;
    }

    public Bandwidth fetchAndResetLastEstimatedBitrate()
    {
        Bandwidth estimatedDataRate = this.estimatedDataRate;
        this.estimatedDataRate = null;
        return estimatedDataRate;
    }

    private void eraseOldClusters(Instant timestamp)
    {
        clusters.entrySet().removeIf((it) ->
            it.getValue().lastReceive.plus(kMaxClusterHistory).isBefore(timestamp)
        );
    }

    private static class AggregatedCluster
    {
        int numProbes = 0;
        Instant firstSend = Instant.MAX;
        Instant lastSend = Instant.MIN;
        Instant firstReceive = Instant.MAX;
        Instant lastReceive = Instant.MIN;
        DataSize sizeLastSend = DataSize.ZERO;
        DataSize sizeFirstReceive = DataSize.ZERO;
        DataSize sizeTotal = DataSize.ZERO;
    }

    /** The minumum number of probes we need to receive feedback about in percent
     * in order to have a valid estimate. */
    public static final double kMinReceivedProbesRatio = 0.80;

    /** The minumum number of bytes we need to receive feedback about in percent
     * in order to have a valid estimate. */
    public static final double kMinReceivedBytesRatio = 0.80;

    /** The maximum |receive rate| / |send rate| ratio for a valid estimate. */
    public static final float kMaxValidRatio = 2.0f;

    /** The minimum |receive rate| / |send rate| ratio assuming that the link is
     * not saturated, i.e. we assume that we will receive at least
     * kMinRatioForUnsaturatedLink * |send rate| if |send rate| is less than the
     * link capacity. */
    public static final double kMinRatioForUnsaturatedLink = 0.9;

    /* The target utilization of the link. If we know true link capacity
     * we'd like to send at 95% of that rate. */
    public static final double kTargetUtilizationFraction = 0.95;

    /* The maximum time period over which the cluster history is retained.
     * This is also the maximum time period beyond which a probing burst is not
     * expected to last. */
    public static final Duration kMaxClusterHistory = DurationKt.getSecs(1);

    /* The maximum time interval between first and the last probe on a cluster
     * on the sender side as well as the receive side. */
    public static final Duration kMaxProbeInterval = DurationKt.getSecs(1);

    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(ProbeBitrateEstimator.class);
}
