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
    // Upstream defaults to GoogleCc2. GoogleCc2 does autonomous bandwidth probing, which — when
    // RTX is not negotiated — falls back to sending dummy padding packets on the media SSRC
    // (see ProbingDataSender.sendDummyData) with a *random* sequence number. On a bridge/SFU that
    // projects forwarded media onto that same SSRC (with a clean, low sequence-number space), that
    // random-seqnum padding stream shadows the real media at the receiver (poisoning its RTP/SRTP
    // sequence baseline), so the receiver decodes nothing. Full jitsi-videobridge deployments avoid
    // this because they always negotiate RTX (probing then goes over the separate RTX SSRC). This
    // library forwards raw RTP without requiring RTX, so it defaults to the classic GoogleCc engine,
    // which jvb also supports and which performs TCC-based estimation without autonomous probing.
    // A user who negotiates RTX can switch this back to GoogleCc2.
    public static final BandwidthEstimatorEngine engine = BandwidthEstimatorEngine.GoogleCc;

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
