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

import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.logging.DiagnosticContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Contains updates of network controller comand state. Using nullables to
 * indicate whether a member has been updated. The array of probe clusters
 * should be used to send out probes if not empty.
 *
 * (Deviation: upstream splits this into an open/immutable {@code NetworkControlUpdate} and an
 * overriding {@code MutableNetworkControlUpdate}; in Java the fields here are already plain
 * mutable fields, so {@link MutableNetworkControlUpdate} is kept only as an alias subclass for
 * upstream-name compatibility at call sites.)
 *
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class NetworkControlUpdate
{
    public DataSize congestionWindow = null;
    public PacerConfig pacerConfig = null;
    public List<ProbeClusterConfig> probeClusterConfigs = new ArrayList<>();
    public TargetTransferRate targetRate = null;

    /* Jitsi local */
    public boolean isEmpty()
    {
        return congestionWindow == null && pacerConfig == null && probeClusterConfigs.isEmpty() && targetRate == null;
    }

    public boolean isNotEmpty()
    {
        return !isEmpty();
    }

    public Instant getAtTime()
    {
        if (targetRate != null)
        {
            return targetRate.atTime;
        }
        if (!probeClusterConfigs.isEmpty())
        {
            return probeClusterConfigs.get(0).atTime;
        }
        if (pacerConfig != null)
        {
            return pacerConfig.atTime;
        }
        return null;
    }

    public void addToTimeSeriesPoint(DiagnosticContext.TimeSeriesPoint point)
    {
        if (targetRate != null)
        {
            point.addField("target_rate_bps", targetRate.targetRate.getBps());
            point.addField("stable_target_rate_bps", targetRate.stableTargetRate.getBps());
            point.addField("cwnd_reduce_ratio", targetRate.cwndReduceRatio);
            point.addField("rtt_ms", DurationKt.toDoubleMillis(targetRate.networkEstimate.roundTripTime));
            point.addField("bwe_period_ms", DurationKt.toDoubleMillis(targetRate.networkEstimate.bwePeriod));
            point.addField("loss_rate_ratio", targetRate.networkEstimate.lossRateRatio);
        }
        if (congestionWindow != null)
        {
            point.addField("congestion_window_bytes", congestionWindow.getBytes());
        }
        if (pacerConfig != null)
        {
            point.addField("pacer_data_rate_bps", pacerConfig.dataRate().getBps());
            point.addField("pacer_pad_rate_bps", pacerConfig.padRate().getBps());
        }
        for (int i = 0; i < probeClusterConfigs.size(); i++)
        {
            ProbeClusterConfig it = probeClusterConfigs.get(i);
            point.addField("probe_cluster_" + i + "_id", it.id);
            point.addField("probe_cluster_" + i + "_data_rate_bps", it.targetDataRate.getBps());
            point.addField("probe_cluster_" + i + "_duration_ms", DurationKt.toDoubleMillis(it.targetDuration));
            point.addField("probe_cluster_" + i + "_delta_ms", DurationKt.toDoubleMillis(it.minProbeDelta));
            point.addField("probe_cluster_" + i + "_count", it.targetProbeCount);
        }
    }
}
