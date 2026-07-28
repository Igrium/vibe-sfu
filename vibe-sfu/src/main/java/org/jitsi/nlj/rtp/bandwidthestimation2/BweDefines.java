/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

import java.time.Duration;

/** Common defines for bandwidth estimation,
 * based on WebRTC modules/remote_bitrate_estimator/{include/bwe_defines.h,bwe_defines.cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * (Deviation: upstream's BweDefines.kt declares BandwidthUsage and RateControlInput as
 * additional top-level declarations in the same file; ported here as their own top-level
 * classes -- BandwidthUsage.java, RateControlInput.java -- in this package, since Java
 * allows only one public top-level type per file and other files reference them
 * unqualified.)
 */
public class BweDefines
{
    public static final Bandwidth kCongestionControllerMinBitrate = Bandwidth.ofBps(5000);

    public static final Duration kBitrateWindow = Duration.ofSeconds(1);

    private BweDefines()
    {
    }
}
