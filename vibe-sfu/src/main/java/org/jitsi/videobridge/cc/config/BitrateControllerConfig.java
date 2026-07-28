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

package org.jitsi.videobridge.cc.config;

import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig;
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimator;
import org.jitsi.nlj.rtp.bandwidthestimation2.GoogCcTransportCcEngine;
import org.jitsi.nlj.util.Bandwidth;

import java.time.Duration;

/**
 * (Deviation: upstream backs these with jitsi-metaconfig/{@code JitsiConfig}, which this library strips. Plain Java
 * holder with the upstream {@code reference.conf} defaults ({@code videobridge.cc.*}) instead.)
 */
public class BitrateControllerConfig
{
    @SuppressWarnings("checkstyle:VisibilityModifier")
    public static final BitrateControllerConfig config = new BitrateControllerConfig();

    /**
     * The bandwidth estimation threshold.
     *
     * In order to limit the resolution changes due to bandwidth changes we only react to bandwidth changes greater
     * than {@code bweChangeThreshold * last_bandwidth_estimation}.
     */
    public final double bweChangeThreshold = 0.15;

    /**
     * The constraint to use for the initial allocation, before the receiver has signaled ReceiverConstraints.
     */
    public final int initialMaxHeightPx = 0;

    /**
     * The default constraint to use if the receiver signals ReceiverConstraints with missing defaultConstraints.
     */
    public final int defaultMaxHeightPx = 180;

    /**
     * The default preferred resolution to allocate for the onstage participant, before allocating bandwidth for
     * the thumbnails.
     */
    public final int onstagePreferredHeightPx = 360;

    /**
     * The preferred frame rate to allocate for the onstage participant.
     */
    public final double onstagePreferredFramerate = 30;

    /**
     * Whether or not we are allowed to oversend (exceed available bandwidth) for the video of the on-stage
     * participant.
     */
    public final boolean allowOversendOnStage = true;

    /**
     * The maximum bitrate by which the bridge may exceed the estimated available bandwidth when oversending.
     */
    public final Bandwidth maxOversendBitrate = Bandwidth.ofKbps(500);

    /**
     * Whether or not we should trust the bandwidth estimations. If this is set to false, then we assume a
     * bandwidth estimation of {@link Long#MAX_VALUE}.
     */
    public final boolean trustBwe = true;

    /**
     * The maximum amount of time we'll run before recalculating which streams we'll forward.
     */
    public final Duration maxTimeBetweenCalculations = Duration.ofSeconds(5);

    /**
     * If set allows receivers to override bandwidth estimation (BWE) with a specific value signaled over the
     * bridge channel (limited to the configured value). If not set, receivers are not allowed to override BWE.
     * Unset by default (commented out in {@code reference.conf}).
     */
    public final Bandwidth assumedBandwidthLimit = null;

    /**
     * Whether to use the target bitrate signaled in the VLA extension for allocation. When disabled we use the
     * measured bitrate instead (preserving previous behavior).
     */
    public final boolean useVlaTargetBitrate = false;

    /**
     * Explicit override of {@link #initialIgnoreBwePeriod}. Unset by default (commented out in
     * {@code reference.conf}), in which case {@link #defaultInitialIgnoreBwePeriod()} is used.
     */
    private final Duration initialIgnoreBwePeriodOverride = null;

    /** The default initial ignore BWE period if not set in jvb.conf, based on the BWE algorithm in use. */
    private Duration defaultInitialIgnoreBwePeriod()
    {
        switch (BandwidthEstimatorConfig.engine)
        {
            case GoogleCc:
                return GoogleCcEstimator.defaultInitialIgnoreBwePeriod;
            case GoogleCc2:
                return GoogCcTransportCcEngine.defaultInitialIgnoreBwePeriod;
            default:
                throw new IllegalStateException("Unknown BWE engine: " + BandwidthEstimatorConfig.engine);
        }
    }

    /**
     * How long after first media to ignore bandwidth estimation. Needed for BWE algorithms that ramp up slowly;
     * should be set to zero if this isn't a problem.
     */
    public Duration getInitialIgnoreBwePeriod()
    {
        return initialIgnoreBwePeriodOverride != null ? initialIgnoreBwePeriodOverride : defaultInitialIgnoreBwePeriod();
    }

    private BitrateControllerConfig()
    {
    }
}
