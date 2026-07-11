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

package org.jitsi.nlj.rtp.bandwidthestimation;

import org.jitsi.nlj.util.Bandwidth;

// (Library change: upstream backs these with metaconfig/JitsiConfig, which this library
// strips. Plain Java holder with the upstream reference.conf defaults
// (jmt.bwe.estimator.*) instead.)
public class BandwidthEstimatorConfig
{
    public static final BandwidthEstimatorEngine engine = BandwidthEstimatorEngine.GoogleCc2;

    public static final Bandwidth initBw = Bandwidth.ofKbps(2500);

    public static final Bandwidth minBw = Bandwidth.ofKbps(30);

    public static final Bandwidth maxBw = Bandwidth.ofMbps(20);

    private BandwidthEstimatorConfig()
    {
    }

    public enum BandwidthEstimatorEngine
    {
        GoogleCc,
        GoogleCc2
    }
}
