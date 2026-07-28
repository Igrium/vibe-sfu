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

package org.jitsi.nlj.transform.node.incoming;

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig;
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator;
import org.jitsi.nlj.rtp.bandwidthestimation2.GoogCcTransportCcEngine;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.BitrateTracker;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.stats.RateTracker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class BitrateCalculator extends ObserverNode
{
    /**
     * The initial period in which we consider the stream active regardless of packet rate.
     */
    public static final Duration GRACE_PERIOD = Duration.ofSeconds(10);

    /**
     * Replaces the upstream {@code jitsi-metaconfig}-based {@code optionalconfig} for
     * {@code jmt.rtp.bitrate-calculator.window-size} / {@code bucket-size}. Since this port does not use
     * {@code jitsi-metaconfig}/HOCON and neither key has a value in the upstream {@code reference.conf} (both are
     * commented out there), the effective default is always {@link #defaultWindowSize()}/{@link
     * #defaultBucketSize()}, which is what this hardcodes -- the ability to override these via config is dropped.
     */
    public static Duration getWindowSize()
    {
        return defaultWindowSize();
    }

    /** The default bitrate calculator window size if not set in jvb.conf, based on the BWE algorithm in use. */
    private static Duration defaultWindowSize()
    {
        switch (BandwidthEstimatorConfig.engine)
        {
            case GoogleCc:
                return GoogleCcEstimator.defaultRateTrackerWindowSize;
            case GoogleCc2:
                return GoogCcTransportCcEngine.defaultRateTrackerWindowSize;
            default:
                throw new IllegalStateException("Unknown engine: " + BandwidthEstimatorConfig.engine);
        }
    }

    public static Duration getBucketSize()
    {
        return defaultBucketSize();
    }

    /** The default bitrate calculator bucket size if not set in jvb.conf, based on the BWE algorithm in use. */
    private static Duration defaultBucketSize()
    {
        switch (BandwidthEstimatorConfig.engine)
        {
            case GoogleCc:
                return GoogleCcEstimator.defaultRateTrackerBucketSize;
            case GoogleCc2:
                return GoogCcTransportCcEngine.defaultRateTrackerBucketSize;
            default:
                throw new IllegalStateException("Unknown engine: " + BandwidthEstimatorConfig.engine);
        }
    }

    public static BitrateTracker createBitrateTracker()
    {
        return new BitrateTracker(getWindowSize(), getBucketSize());
    }

    public static RateTracker createRateTracker()
    {
        return new RateTracker(getWindowSize(), getBucketSize());
    }

    /**
     * At what threshold the stream is considered active.
     */
    private final int activePacketRateThreshold;

    protected final Clock clock;

    private final BitrateTracker bitrateTracker = createBitrateTracker();
    private final RateTracker packetRateTracker = createRateTracker();
    private final Instant start;

    public BitrateCalculator()
    {
        this("Bitrate calculator", 5, Clock.systemUTC());
    }

    public BitrateCalculator(String name)
    {
        this(name, 5, Clock.systemUTC());
    }

    public BitrateCalculator(String name, int activePacketRateThreshold)
    {
        this(name, activePacketRateThreshold, Clock.systemUTC());
    }

    public BitrateCalculator(String name, int activePacketRateThreshold, Clock clock)
    {
        super(name);
        this.activePacketRateThreshold = activePacketRateThreshold;
        this.clock = clock;
        this.start = clock.instant();
    }

    public Bandwidth getBitrate()
    {
        return bitrateTracker.getRate();
    }

    public long getPacketRatePps()
    {
        return packetRateTracker.getRate();
    }

    /**
     * Keep track of whether the stream is active (has packets at at least {@link #activePacketRateThreshold})
     */
    public boolean isActive()
    {
        if (Duration.between(start, clock.instant()).compareTo(GRACE_PERIOD) <= 0)
        {
            // In the grace period any received data counts, and we check the bitrate because we can only access
            // the packet rate rounded to an Int.
            return getBitrate().getBps() > 0;
        }
        else
        {
            return getPacketRatePps() >= activePacketRateThreshold;
        }
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        long now = clock.millis();
        bitrateTracker.update(DataSize.ofBytes(packetInfo.getPacket().getLength()), now);
        packetRateTracker.update(1, now);
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("bitrate_bps", getBitrate().getBps());
        block.addNumber("packet_rate_pps", getPacketRatePps());
        block.addBoolean("active", isActive());
        return block;
    }

    @Override
    protected NodeStatsBlock getNodeStatsToAggregate()
    {
        return super.getNodeStats();
    }
}
