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

/**
 * Information about a paced packet.
 *
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class PacedPacketInfo
{
    public static final int kNotAProbe = -1;

    public int probeClusterId;
    public int probeClusterMinProbes;
    public int probeClusterMinBytes;
    public Bandwidth sendBitrate;

    // TODO(srte): Move probing info to a separate, optional struct.
    public int probeClusterBytesSent = 0;

    public PacedPacketInfo()
    {
        this(kNotAProbe, -1, -1, Bandwidth.ofBps(0));
    }

    public PacedPacketInfo(int probeClusterId, int probeClusterMinProbes, int probeClusterMinBytes, Bandwidth sendBitrate)
    {
        this.probeClusterId = probeClusterId;
        this.probeClusterMinProbes = probeClusterMinProbes;
        this.probeClusterMinBytes = probeClusterMinBytes;
        this.sendBitrate = sendBitrate;
    }

    public PacedPacketInfo copy()
    {
        PacedPacketInfo c = new PacedPacketInfo(probeClusterId, probeClusterMinProbes, probeClusterMinBytes, sendBitrate);
        c.probeClusterBytesSent = probeClusterBytesSent;
        return c;
    }
}
