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

/** Represents constraints and rates related to the currently enabled streams.
 * This is used as input to the congestion controller via the StreamsConfig
 * struct.
 *
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class BitrateAllocationLimits
{
    /** The total minimum send bitrate required by all sending streams. */
    public final Bandwidth minAllocatableRate;
    /** The total maximum allocatable bitrate for all currently available streams. */
    public final Bandwidth maxAllocatableRate;
    /** The max bitrate to use for padding. The sum of the per-stream max padding
     * rate. */
    public final Bandwidth maxPaddingRate;

    public BitrateAllocationLimits(Bandwidth minAllocatableRate, Bandwidth maxAllocatableRate, Bandwidth maxPaddingRate)
    {
        this.minAllocatableRate = minAllocatableRate;
        this.maxAllocatableRate = maxAllocatableRate;
        this.maxPaddingRate = maxPaddingRate;
    }

    public BitrateAllocationLimits()
    {
        this(Bandwidth.ZERO, Bandwidth.ZERO, Bandwidth.ZERO);
    }
}
