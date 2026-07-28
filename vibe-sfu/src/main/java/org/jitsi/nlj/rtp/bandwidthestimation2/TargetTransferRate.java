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

import java.time.Instant;

/**
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class TargetTransferRate
{
    public Instant atTime = Instant.MAX;

    // The estimate on which the target rate is based on.
    public NetworkEstimate networkEstimate = new NetworkEstimate();
    public Bandwidth targetRate = Bandwidth.ZERO;
    public Bandwidth stableTargetRate = Bandwidth.ZERO;
    public double cwndReduceRatio = 0.0;

    /* Jitsi local */
    @Override
    public String toString()
    {
        return "atTime " + atTime + ": networkEstimate {" + networkEstimate + "}, " +
            "targetRate " + targetRate + ", stableTargetRate " + stableTargetRate +
            ", cwndReduceRatio " + cwndReduceRatio;
    }
}
