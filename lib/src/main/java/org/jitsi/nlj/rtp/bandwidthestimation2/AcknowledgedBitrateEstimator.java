/*
 * Copyright @ 2019-present 8x8, Inc
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
import org.jitsi.nlj.util.DataSize;

import java.time.Instant;
import java.util.List;

/**
 * Basic implementation to estimate acknowledged bitrate.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/acknowledged_bitrate_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class AcknowledgedBitrateEstimator implements AcknowledgedBitrateEstimatorInterface
{
    public final BitrateEstimator bitrateEstimator;

    private Instant alrEndedTime = null;
    private boolean inAlr = false;

    public AcknowledgedBitrateEstimator(BitrateEstimator bitrateEstimator)
    {
        this.bitrateEstimator = bitrateEstimator;
    }

    public AcknowledgedBitrateEstimator()
    {
        this(new BitrateEstimator());
    }

    @Override
    public void incomingPacketFeedbackVector(List<PacketResult> packetFeedbackVector)
    {
        if (!isSortedByReceiveTime(packetFeedbackVector))
        {
            throw new IllegalStateException("Check failed: packetFeedbackVector is not sorted by receive time");
        }
        for (PacketResult packet : packetFeedbackVector)
        {
            if (alrEndedTime != null && packet.sentPacket.sendTime.isAfter(alrEndedTime))
            {
                bitrateEstimator.expectFastRateChange();
                alrEndedTime = null;
            }
            DataSize acknowledgedEstimate = packet.sentPacket.size.plus(packet.sentPacket.priorUnackedData);
            bitrateEstimator.update(packet.receiveTime, acknowledgedEstimate, inAlr);
        }
    }

    @Override
    public Bandwidth bitrate()
    {
        return bitrateEstimator.bitrate();
    }

    @Override
    public Bandwidth peekRate()
    {
        return bitrateEstimator.peekRate();
    }

    @Override
    public void setAlr(boolean inAlr)
    {
        this.inAlr = inAlr;
    }

    @Override
    public void setAlrEndedTime(Instant alrEndedTime)
    {
        this.alrEndedTime = alrEndedTime;
    }

    private static boolean isSortedByReceiveTime(List<PacketResult> packetFeedbackVector)
    {
        for (int i = 0; i + 1 < packetFeedbackVector.size(); i++)
        {
            if (packetFeedbackVector.get(i).receiveTime.isAfter(packetFeedbackVector.get(i + 1).receiveTime))
            {
                return false;
            }
        }
        return true;
    }
}
