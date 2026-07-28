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

import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;

import java.time.Duration;
import java.time.Instant;

/**
 * Helper class to compute the inter-arrival time delta and the size delta
 * between two timestamp groups. This code is branched from
 * modules/remote_bitrate_estimator/inter_arrival.
 * *
 * Based on WebRTC modules/congestion_controller/goog_cc/inter_arrival_delta.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class InterArrivalDelta
{
    /**
     * After this many packet groups received out of order InterArrival will
     * reset, assuming that clocks have made a jump.
     */
    public static final int kReorderedResetThreshold = 3;

    public static final Duration kArrivalTimeOffsetThreshold = Duration.ofSeconds(3);

    public static final Duration kBurstDeltaThreshold = Duration.ofMillis(5);
    public static final Duration kMaxBurstDuration = Duration.ofMillis(100);

    private final Duration sendTimeGroupLength;

    // TODO: pass parent logger in so we have log contexts
    private final Logger logger = new LoggerImpl(InterArrivalDelta.class.getName());

    private SendTimeGroup currentTimestampGroup = new SendTimeGroup();
    private SendTimeGroup prevTimestampGroup = new SendTimeGroup();
    private int numConsecutiveReorderedPackets = 0;

    public InterArrivalDelta(Duration sendTimeGroupLength)
    {
        this.sendTimeGroupLength = sendTimeGroupLength;
    }

    public static class ComputeDeltasResult
    {
        public final boolean computed;
        public final Duration sendTimeDelta;
        public final Duration arrivalTimeDelta;
        public final int packetSizeDelta;

        public ComputeDeltasResult(
            boolean computed, Duration sendTimeDelta, Duration arrivalTimeDelta, int packetSizeDelta)
        {
            this.computed = computed;
            this.sendTimeDelta = sendTimeDelta;
            this.arrivalTimeDelta = arrivalTimeDelta;
            this.packetSizeDelta = packetSizeDelta;
        }

        public ComputeDeltasResult(boolean computed)
        {
            this(computed, Duration.ZERO, Duration.ZERO, 0);
        }
    }

    public ComputeDeltasResult computeDeltas(
        Instant sendTime,
        Instant arrivalTime,
        Instant systemTime,
        long packetSize)
    {
        Duration sendTimeDelta = Duration.ZERO;
        Duration arrivalTimeDelta = Duration.ZERO;
        int packetSizeDelta = 0;

        boolean calculatedDeltas = false;

        if (currentTimestampGroup.isFirstPacket())
        {
            // We don't have enough data to update the filter, so we store it until we
            // have two frames of data to process.
            currentTimestampGroup.sendTime = sendTime;
            currentTimestampGroup.firstSendTime = sendTime;
            currentTimestampGroup.firstArrival = arrivalTime;
        }
        else if (currentTimestampGroup.firstSendTime.isAfter(sendTime))
        {
            // Reordered packet
            return new ComputeDeltasResult(false);
        }
        else if (newTimestampGroup(arrivalTime, sendTime))
        {
            // First packet of a later frame, the previous frame sample is ready.
            if (!prevTimestampGroup.completeTime.equals(InstantKt.NEVER))
            {
                sendTimeDelta = Duration.between(prevTimestampGroup.sendTime, currentTimestampGroup.sendTime);
                arrivalTimeDelta =
                    Duration.between(prevTimestampGroup.completeTime, currentTimestampGroup.completeTime);
                Duration systemTimeDelta =
                    Duration.between(prevTimestampGroup.lastSystemTime, currentTimestampGroup.lastSystemTime);

                if (arrivalTimeDelta.minus(systemTimeDelta).compareTo(kArrivalTimeOffsetThreshold) >= 0)
                {
                    logger.warn(
                        "The arrival clock offset has changed (diff = "
                            + arrivalTimeDelta.minus(systemTimeDelta) + "), resetting");
                    reset();
                    return new ComputeDeltasResult(false);
                }
                if (arrivalTimeDelta.compareTo(Duration.ZERO) < 0)
                {
                    // The group of packets has been reordered since receiving its local
                    // arrival timestamp.
                    ++numConsecutiveReorderedPackets;
                    if (numConsecutiveReorderedPackets >= kReorderedResetThreshold)
                    {
                        logger.warn(
                            "Packets between send burst arrived out of order, restting: "
                                + "arrivalTimeDelta=" + arrivalTimeDelta + ", sendTimeDelta=" + sendTimeDelta);
                        reset();
                    }
                    return new ComputeDeltasResult(false);
                }
                else
                {
                    numConsecutiveReorderedPackets = 0;
                }
                packetSizeDelta = (int) currentTimestampGroup.size - (int) prevTimestampGroup.size;
                calculatedDeltas = true;
            }
            prevTimestampGroup = currentTimestampGroup.copy();
            // The new timestamp is now the current frame
            currentTimestampGroup.firstSendTime = sendTime;
            currentTimestampGroup.sendTime = sendTime;
            currentTimestampGroup.firstArrival = arrivalTime;
            currentTimestampGroup.size = 0;
        }
        else
        {
            currentTimestampGroup.sendTime = InstantKt.max(currentTimestampGroup.sendTime, sendTime);
        }
        // Accumulate the frame size.
        currentTimestampGroup.size += packetSize;
        currentTimestampGroup.completeTime = arrivalTime;
        currentTimestampGroup.lastSystemTime = systemTime;

        return new ComputeDeltasResult(calculatedDeltas, sendTimeDelta, arrivalTimeDelta, packetSizeDelta);
    }

    private boolean newTimestampGroup(Instant arrivalTime, Instant sendTime)
    {
        if (currentTimestampGroup.isFirstPacket())
        {
            return false;
        }
        else if (belongsToBurst(arrivalTime, sendTime))
        {
            return false;
        }
        else
        {
            return Duration.between(currentTimestampGroup.firstSendTime, sendTime)
                .compareTo(sendTimeGroupLength) > 0;
        }
    }

    private boolean belongsToBurst(Instant arrivalTime, Instant sendTime)
    {
        if (currentTimestampGroup.completeTime.equals(InstantKt.NEVER))
        {
            throw new IllegalStateException("Check failed: currentTimestampGroup.completeTime != NEVER");
        }
        Duration arrivalTimeDelta = Duration.between(currentTimestampGroup.completeTime, arrivalTime);
        Duration sendTimeDelta = Duration.between(currentTimestampGroup.sendTime, sendTime);
        if (sendTimeDelta.equals(Duration.ZERO))
        {
            return true;
        }
        Duration propagationDelta = arrivalTimeDelta.minus(sendTimeDelta);
        if (propagationDelta.compareTo(Duration.ZERO) < 0 &&
            arrivalTimeDelta.compareTo(kBurstDeltaThreshold) <= 0 &&
            Duration.between(currentTimestampGroup.firstArrival, arrivalTime).compareTo(kMaxBurstDuration) < 0)
        {
            return true;
        }
        return false;
    }

    private void reset()
    {
        numConsecutiveReorderedPackets = 0;
        currentTimestampGroup = new SendTimeGroup();
        prevTimestampGroup = new SendTimeGroup();
    }

    public static class SendTimeGroup
    {
        public long size = 0;
        public Instant firstSendTime = InstantKt.NEVER;
        public Instant sendTime = InstantKt.NEVER;
        public Instant firstArrival = InstantKt.NEVER;
        public Instant completeTime = InstantKt.NEVER;
        public Instant lastSystemTime = InstantKt.NEVER;

        public boolean isFirstPacket()
        {
            return completeTime.equals(InstantKt.NEVER);
        }

        public SendTimeGroup copy()
        {
            SendTimeGroup c = new SendTimeGroup();
            c.size = size;
            c.firstSendTime = firstSendTime;
            c.sendTime = sendTime;
            c.firstArrival = firstArrival;
            c.completeTime = completeTime;
            c.lastSystemTime = lastSystemTime;
            return c;
        }
    }
}
