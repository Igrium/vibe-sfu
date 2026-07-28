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
import org.jitsi.utils.DurationKt;

import java.time.Duration;
import java.time.Instant;

/**
 * Network estimation.
 *
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class NetworkEstimate
{
    public Instant atTime = Instant.MAX;

    // Deprecated, use TargetTransferRate::target_rate instead.
    public Bandwidth bandwidth = Bandwidth.INFINITY;
    public Duration roundTripTime = DurationKt.getMAX_DURATION();
    public Duration bwePeriod = DurationKt.getMAX_DURATION();

    public float lossRateRatio = 0.0f;

    /* Jitsi local */
    @Override
    public String toString()
    {
        return "atTime " + atTime + ": " +
            "bandwidth " + bandwidth + ", rtt " + roundTripTime + ", bwePeriod " + bwePeriod +
            ", lossRateRatio " + lossRateRatio;
    }
}
