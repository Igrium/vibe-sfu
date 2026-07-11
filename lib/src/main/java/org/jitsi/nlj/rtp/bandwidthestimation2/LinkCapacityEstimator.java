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

/** Link capacity estimator,
 * based on WebRTC modules/congestion_controller/goog_cc/link_capacity_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class LinkCapacityEstimator
{
    private Double estimateKbps = null;
    private double deviationKbps = 0.4;

    public Bandwidth upperBound()
    {
        if (estimateKbps == null)
        {
            return Bandwidth.INFINITY;
        }
        return Bandwidth.ofKbps(estimateKbps + 3 * deviationEstimateKbps());
    }

    public Bandwidth lowerBound()
    {
        if (estimateKbps == null)
        {
            return Bandwidth.ZERO;
        }
        return Bandwidth.ofKbps(Math.max(0.0, estimateKbps - 3 * deviationEstimateKbps()));
    }

    public void reset()
    {
        estimateKbps = null;
    }

    public void onOveruseDetected(Bandwidth acknowledgedRate)
    {
        update(acknowledgedRate, 0.05);
    }

    public void onProbeRate(Bandwidth probeRate)
    {
        update(probeRate, 0.5);
    }

    public boolean hasEstimate()
    {
        return estimateKbps != null;
    }

    public Bandwidth getEstimate()
    {
        return estimateKbps == null ? null : Bandwidth.ofKbps(estimateKbps);
    }

    private void update(Bandwidth capacitySample, double alpha)
    {
        // This double-cast is probably a bug in the Google CC code (it calls `capacity_sample.kbps()` rather than
        // `capacity_sample.kbps<double>()`) but emulate it to be bit-exact.
        double sampleKbps = (double) (long) capacitySample.getKbps();
        if (estimateKbps == null)
        {
            estimateKbps = sampleKbps;
        }
        else
        {
            estimateKbps = (1 - alpha) * estimateKbps + alpha * sampleKbps;
        }
        // Estimate the variance of the link capacity estimate and normalize the
        // variance with the link capacity estimate.
        double norm = Math.max(estimateKbps, 1.0);
        double errorKbps = estimateKbps - sampleKbps;
        deviationKbps = (1 - alpha) * deviationKbps + alpha * errorKbps * errorKbps / norm;
        // 0.4 ~= 14 kbit/s at 500 kbit/s
        // 2.5 ~= 35 kbit/s at 500 kbit/s
        deviationKbps = Math.min(Math.max(deviationKbps, 0.4), 2.5);
    }

    private double deviationEstimateKbps()
    {
        // Calculate the max bit rate std dev given the normalized
        // variance and the current throughput bitrate. The standard deviation will
        // only be used if estimateKbps has a value.
        return Math.sqrt(deviationKbps * estimateKbps);
    }
}
