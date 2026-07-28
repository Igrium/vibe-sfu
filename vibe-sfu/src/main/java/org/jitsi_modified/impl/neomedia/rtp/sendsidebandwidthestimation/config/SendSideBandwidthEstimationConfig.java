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
package org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.config;

import org.jitsi.nlj.util.Bandwidth;

// (Library change: upstream backs these with metaconfig/JitsiConfig (including a legacy
// XMPP/deployment config path), which this library strips. Plain Java holder with the
// upstream reference.conf defaults (jmt.bwe.send-side.*) instead. The two legacy-config
// experiment overrides have no equivalent here; the "experimental" values fall back to the
// same reference.conf defaults as the loss-experiment/timeout-experiment blocks
// (${jmt.bwe.send-side.*} interpolation upstream), and the experiment probabilities default
// to 0 (i.e. disabled) as upstream ships them.)
public class SendSideBandwidthEstimationConfig
{
    private static final double defaultLowLossThreshold = 0.02;

    public static double defaultLowLossThreshold()
    {
        return defaultLowLossThreshold;
    }

    private static final double defaultHighLossThreshold = 0.1;

    public static double defaultHighLossThreshold()
    {
        return defaultHighLossThreshold;
    }

    private static final Bandwidth defaultBitrateThreshold = Bandwidth.ofKbps(100);

    public static Bandwidth defaultBitrateThreshold()
    {
        return defaultBitrateThreshold;
    }

    public static double defaultBitrateThresholdBps()
    {
        return defaultBitrateThreshold.getBps();
    }

    private static final double lossExperimentProbability = 0;

    public static double lossExperimentProbability()
    {
        return lossExperimentProbability;
    }

    // jmt.bwe.send-side.loss-experiment.low-loss-threshold = ${jmt.bwe.send-side.low-loss-threshold}
    private static final double experimentalLowLossThreshold = defaultLowLossThreshold;

    public static double experimentalLowLossThreshold()
    {
        return experimentalLowLossThreshold;
    }

    // jmt.bwe.send-side.loss-experiment.high-loss-threshold = ${jmt.bwe.send-side.high-loss-threshold}
    private static final double experimentalHighLossThreshold = defaultHighLossThreshold;

    public static double experimentalHighLossThreshold()
    {
        return experimentalHighLossThreshold;
    }

    // jmt.bwe.send-side.loss-experiment.bitrate-threshold = ${jmt.bwe.send-side.bitrate-threshold}
    private static final Bandwidth experimentalBitrateThreshold = defaultBitrateThreshold;

    public static Bandwidth experimentalBitrateThreshold()
    {
        return experimentalBitrateThreshold;
    }

    public static double experimentalBitrateThresholdBps()
    {
        return experimentalBitrateThreshold.getBps();
    }

    private static final double timeoutExperimentProbability = 0;

    public static double timeoutExperimentProbability()
    {
        return timeoutExperimentProbability;
    }

    private SendSideBandwidthEstimationConfig()
    {
    }
}
