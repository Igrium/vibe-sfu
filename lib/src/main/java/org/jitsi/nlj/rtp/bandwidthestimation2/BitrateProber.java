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

import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging2.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

/** Bitrate prober,
 * based on WebRTC modules/pacing/bitrate_prober.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class BitrateProber
{
    private static final Duration kProbeClusterTimeout = DurationKt.getSecs(5);
    private static final int kMaxPendingProbeClusters = 5;

    private final Logger logger;

    public BitrateProber(Logger parentLogger, BitrateProberConfig configIn)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.config = configIn.copy();
    }

    public BitrateProber(Logger parentLogger)
    {
        this(parentLogger, new BitrateProberConfig());
    }

    public void setEnabled(boolean enable)
    {
        if (enable)
        {
            if (probingState == ProbingState.kDisabled)
            {
                probingState = ProbingState.kInactive;
                logger.info("Bandwidth probing enabled, set to inactive");
            }
        }
        else
        {
            probingState = ProbingState.kDisabled;
            logger.info("Bandwidth probing disabled");
        }
    }

    public void setAllowProbeWithoutMediaPacket(boolean allow)
    {
        config.allowStartProbingImmediately = allow;
        maybeSetActiveState(DataSize.ZERO);
    }

    // Returns true if the prober is in a probing session, i.e., it currently
    // wants packets to be sent out according to the time returned by
    // TimeUntilNextProbe().
    public boolean isProbing()
    {
        return probingState == ProbingState.kActive;
    }

    // Initializes a new probing session if the prober is allowed to probe. Does
    // not initialize the prober unless the packet size is large enough to probe
    // with.
    public void onIncomingPacket(DataSize packetSize)
    {
        maybeSetActiveState(packetSize);
    }

    // Create a cluster used to probe.
    public void createProbeCluster(ProbeClusterConfig clusterConfig)
    {
        if (probingState == ProbingState.kDisabled)
        {
            throw new IllegalStateException("Check failed: probingState != ProbingState.kDisabled");
        }
        if (clusterConfig.minProbeDelta.compareTo(Duration.ZERO) <= 0)
        {
            throw new IllegalStateException("Check failed: clusterConfig.minProbeDelta > Duration.ZERO");
        }

        while (!clusters.isEmpty() && (
            Duration.between(clusters.getFirst().requestedAt, clusterConfig.atTime)
                .compareTo(kProbeClusterTimeout) > 0 ||
                clusters.size() > kMaxPendingProbeClusters
            ))
        {
            clusters.removeFirst();
        }

        ProbeCluster cluster = new ProbeCluster(
            new PacedPacketInfo(
                /* probeClusterId = */ clusterConfig.id,
                /* probeClusterMinProbes = */ clusterConfig.targetProbeCount,
                /* probeClusterMinBytes = */
                (int) clusterConfig.targetDataRate.times(clusterConfig.targetDuration).getBytes(),
                /* sendBitrate = */ Bandwidth.ofBps(0)
            )
        );
        cluster.requestedAt = clusterConfig.atTime;
        cluster.minProbeDelta = clusterConfig.minProbeDelta;
        if (cluster.paceInfo.probeClusterMinBytes < 0)
        {
            throw new IllegalStateException("Check failed: cluster.paceInfo.probeClusterMinBytes >= 0");
        }
        cluster.paceInfo.sendBitrate = clusterConfig.targetDataRate;
        clusters.addLast(cluster);

        maybeSetActiveState(/* packetSize = */ DataSize.ZERO);

        if (!(probingState == ProbingState.kActive || probingState == ProbingState.kInactive))
        {
            throw new IllegalStateException(
                "Check failed: probingState == ProbingState.kActive || probingState == ProbingState.kInactive");
        }

        logger.info(() ->
            "Probe cluster (bitrate_bps:min bytes:min packets): (" +
                cluster.paceInfo.sendBitrate + ":" + cluster.paceInfo.probeClusterMinBytes + ":" +
                cluster.paceInfo.probeClusterMinProbes + ", " + probingState + ")"
        );
    }

    // Returns the time at which the next probe should be sent to get accurate
    // probing. If probing is not desired at this time, [Instant.MAX]
    // will be returned.
    // TODO(bugs.webrtc.org/11780): Remove `now` argument when old mode is gone.
    public Instant nextProbeTime(Instant now)
    {
        // Probing is not active or probing is already complete.
        if (probingState != ProbingState.kActive || clusters.isEmpty())
        {
            return Instant.MAX;
        }
        return nextProbeTime;
    }

    // Information about the current probing cluster.
    public PacedPacketInfo currentCluster(Instant now)
    {
        if (clusters.isEmpty() || probingState != ProbingState.kActive)
        {
            return null;
        }

        if (InstantKt.isFinite(nextProbeTime) &&
            Duration.between(nextProbeTime, now).compareTo(config.maxProbeDelay) > 0)
        {
            logger.warn(
                "Probe delay too high (next:" + nextProbeTime + ", now:" + now + ")" +
                    "discarding probe cluster."
            );
            clusters.removeFirst();
            if (clusters.isEmpty())
            {
                probingState = ProbingState.kInactive;
                return null;
            }
        }

        PacedPacketInfo info = clusters.getFirst().paceInfo.copy();
        info.probeClusterBytesSent = clusters.getFirst().sentBytes;
        return info;
    }

    // Returns the minimum number of bytes that the prober recommends for
    // the next probe, or zero if not probing. A probe can consist of multiple
    // packets that are sent back to back.
    public DataSize recommendedMinProbeSize()
    {
        if (clusters.isEmpty())
        {
            return DataSize.ZERO;
        }
        Bandwidth sendRate = clusters.getFirst().paceInfo.sendBitrate;
        return sendRate.times(clusters.getFirst().minProbeDelta);
    }

    // Called to report to the prober that a probe has been sent. In case of
    // multiple packets per probe, this call would be made at the end of sending
    // the last packet in probe. `size` is the total size of all packets in probe.
    public void probeSent(Instant now, DataSize size)
    {
        if (probingState != ProbingState.kActive)
        {
            throw new IllegalStateException("Check failed: probingState == ProbingState.kActive");
        }
        if (size.equals(DataSize.ZERO))
        {
            throw new IllegalStateException("Check failed: size != DataSize.ZERO");
        }

        if (!clusters.isEmpty())
        {
            ProbeCluster cluster = clusters.getFirst();
            if (cluster.sentProbes == 0)
            {
                if (!InstantKt.isInfinite(cluster.startedAt))
                {
                    throw new IllegalStateException("Check failed: cluster.startedAt.isInfinite()");
                }
                cluster.startedAt = now;
            }
            cluster.sentBytes += (int) size.getBytes();
            cluster.sentProbes += 1;
            nextProbeTime = calculateNextProbeTime(cluster);
            if (cluster.sentBytes >= cluster.paceInfo.probeClusterMinBytes &&
                cluster.sentProbes >= cluster.paceInfo.probeClusterMinProbes)
            {
                clusters.removeFirst();
            }
            if (clusters.isEmpty())
            {
                probingState = ProbingState.kInactive;
            }
        }
    }

    private enum ProbingState
    {
        // Probing will not be triggered in this state at all times.
        kDisabled,

        // Probing is enabled and ready to trigger on the first packet arrival if
        // there is a probe cluster.
        kInactive,

        // Probe cluster is filled with the set of data rates to be probed and
        // probes are being sent.
        kActive,
    }

    // A probe cluster consists of a set of probes. Each probe in turn can be
    // divided into a number of packets to accommodate the MTU on the network.
    private static class ProbeCluster
    {
        final PacedPacketInfo paceInfo;
        int sentProbes = 0;
        int sentBytes = 0;
        Duration minProbeDelta = Duration.ZERO;
        Instant requestedAt = Instant.MIN;
        Instant startedAt = Instant.MIN;

        ProbeCluster(PacedPacketInfo paceInfo)
        {
            this.paceInfo = paceInfo;
        }
    }

    private Instant calculateNextProbeTime(ProbeCluster cluster)
    {
        if (cluster.paceInfo.sendBitrate.compareTo(Bandwidth.ofBps(0)) < 0)
        {
            throw new IllegalStateException("Check failed: cluster.paceInfo.sendBitrate >= 0.bps");
        }
        if (!InstantKt.isFinite(cluster.startedAt))
        {
            throw new IllegalStateException("Check failed: cluster.startedAt.isFinite()");
        }

        // Compute the time delta from the cluster start to ensure probe bitrate stays
        // close to the target bitrate. Result is in milliseconds.
        DataSize sentBytes = DataSize.ofBytes(cluster.sentBytes);
        Bandwidth sendBitrate = cluster.paceInfo.sendBitrate;

        Duration delta = sentBytes.div(sendBitrate);
        return cluster.startedAt.plus(delta);
    }

    private void maybeSetActiveState(DataSize packetSize)
    {
        if (readyToSetActiveState(packetSize))
        {
            nextProbeTime = Instant.MIN;
            probingState = ProbingState.kActive;
        }
    }

    private boolean readyToSetActiveState(DataSize packetSize)
    {
        if (clusters.isEmpty())
        {
            if (!(probingState == ProbingState.kDisabled || probingState == ProbingState.kInactive))
            {
                throw new IllegalStateException(
                    "Check failed: probingState == ProbingState.kDisabled || " +
                        "probingState == ProbingState.kInactive");
            }
            return false;
        }
        switch (probingState)
        {
            case kDisabled:
            case kActive:
                return false;
            case kInactive:
                if (config.allowStartProbingImmediately)
                {
                    return true;
                }
                // If config_.min_packet_size > 0, a "large enough" packet must be
                // sent first, before a probe can be generated and sent. Otherwise,
                // send the probe asap.
                return packetSize.compareTo(DataSize.min(recommendedMinProbeSize(), config.minPacketSize)) >= 0;
            default:
                throw new IllegalStateException("Invalid probing state " + probingState);
        }
    }

    private ProbingState probingState = ProbingState.kInactive;

    // Probe bitrate per packet. These are used to compute the delta relative to
    // the previous probe packet based on the size and time when that packet was
    // sent.
    private final ArrayDeque<ProbeCluster> clusters = new ArrayDeque<>();

    // Time that the next probe should be sent when in kActive state
    private Instant nextProbeTime = Instant.MAX;

    private final BitrateProberConfig config;
}

class BitrateProberConfig
{
    Duration maxProbeDelay;
    DataSize minPacketSize;
    boolean allowStartProbingImmediately;

    BitrateProberConfig(Duration maxProbeDelay, DataSize minPacketSize, boolean allowStartProbingImmediately)
    {
        this.maxProbeDelay = maxProbeDelay;
        this.minPacketSize = minPacketSize;
        this.allowStartProbingImmediately = allowStartProbingImmediately;
    }

    BitrateProberConfig()
    {
        this(DurationKt.getMs(20), DataSize.ofBytes(200), false);
    }

    BitrateProberConfig copy()
    {
        return new BitrateProberConfig(maxProbeDelay, minPacketSize, allowStartProbingImmediately);
    }
}
