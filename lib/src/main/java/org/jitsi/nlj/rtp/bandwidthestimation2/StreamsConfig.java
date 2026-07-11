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
 * Use StreamsConfig for information about streams that is required for specific
 * adjustments to the algorithms in network controllers. Especially useful
 * for experiments.
 *
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class StreamsConfig
{
    public Instant atTime = Instant.MAX;
    public Boolean requestsAlrProbing = null;
    // If `enable_repeated_initial_probing` is set to true, Probes are sent
    // periodically every 1s during the first 5s after the network becomes
    // available. The probes ignores max_total_allocated_bitrate.
    public Boolean enableRepeatedInitialProbing = null;

    public Double pacingFactor = null;

    // TODO(srte): Use BitrateAllocationLimits here.
    public Bandwidth minTotalAllocatedBitrate = null;
    public Bandwidth maxPaddingRate = null;
    public Bandwidth maxTotalAllocatedBitrate = null;
}
