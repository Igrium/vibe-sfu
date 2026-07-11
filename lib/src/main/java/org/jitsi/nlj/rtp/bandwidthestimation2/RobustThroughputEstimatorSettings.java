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

import java.time.Duration;

public class RobustThroughputEstimatorSettings
{
    // Set `enabled` to true to use the RobustThroughputEstimator, false to use
    // the AcknowledgedBitrateEstimator.
    public final boolean enabled;

    // The estimator keeps the smallest window containing at least
    // `window_packets` and at least the packets received during the last
    // `min_window_duration` milliseconds.
    // (This means that it may store more than `window_packets` at high bitrates,
    // and a longer duration than `min_window_duration` at low bitrates.)
    // However, if will never store more than kMaxPackets (for performance
    // reasons), and never longer than max_window_duration (to avoid very old
    // packets influencing the estimate for example when sending is paused).
    public final int windowPackets;
    public final int maxWindowPackets;
    public final Duration minWindowDuration;
    public final Duration maxWindowDuration;

    // The estimator window requires at least `required_packets` packets
    // to produce an estimate.
    public final int requiredPackets;

    // If audio packets aren't included in allocation (i.e. the
    // estimated available bandwidth is divided only among the video
    // streams), then `unacked_weight` should be set to 0.
    // If audio packets are included in allocation, but not in bandwidth
    // estimation (i.e. they don't have transport-wide sequence numbers,
    // but we nevertheless divide the estimated available bandwidth among
    // both audio and video streams), then `unacked_weight` should be set to 1.
    // If all packets have transport-wide sequence numbers, then the value
    // of `unacked_weight` doesn't matter.
    public final double unackedWeight;

    public RobustThroughputEstimatorSettings(
        boolean enabled,
        int windowPackets,
        int maxWindowPackets,
        Duration minWindowDuration,
        Duration maxWindowDuration,
        int requiredPackets,
        double unackedWeight)
    {
        this.enabled = enabled;
        this.windowPackets = windowPackets;
        this.maxWindowPackets = maxWindowPackets;
        this.minWindowDuration = minWindowDuration;
        this.maxWindowDuration = maxWindowDuration;
        this.requiredPackets = requiredPackets;
        this.unackedWeight = unackedWeight;
    }

    public RobustThroughputEstimatorSettings()
    {
        this(true, 20, 500, Duration.ofSeconds(1), Duration.ofSeconds(5), 10, 1.0);
    }
}
