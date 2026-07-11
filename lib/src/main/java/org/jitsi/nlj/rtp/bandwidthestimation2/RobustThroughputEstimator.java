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
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Robust throughput estimator.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/robust_throughput_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138)
 */
public class RobustThroughputEstimator implements AcknowledgedBitrateEstimatorInterface
{
    public final RobustThroughputEstimatorSettings settings;

    // TODO: pass parent logger in so we have log contexts
    private final Logger logger = new LoggerImpl(RobustThroughputEstimator.class.getName());

    /* Kotlin ArrayDeque (indexed access + O(1) ends); ArrayList is close enough here
     * since the window is bounded by settings.maxWindowPackets. */
    private final ArrayList<PacketResult> window = new ArrayList<>();

    private Instant latestDiscardedSendTime = Instant.MIN;

    public RobustThroughputEstimator(RobustThroughputEstimatorSettings settings)
    {
        if (!settings.enabled)
        {
            throw new IllegalArgumentException("settings.enabled must be true");
        }
        this.settings = settings;
    }

    @Override
    public void incomingPacketFeedbackVector(List<PacketResult> packetFeedbackVector)
    {
        if (!isSorted(packetFeedbackVector, Comparator.comparing(p -> p.receiveTime)))
        {
            throw new IllegalArgumentException("packetFeedbackVector must be sorted by receiveTime");
        }
        for (PacketResult packet : packetFeedbackVector)
        {
            // Ignore packets without valid send or receive times.
            // (This should not happen in production since lost packets are filtered
            // out before passing the feedback vector to the throughput estimator.
            // However, explicitly handling this case makes the estimator more robust
            // and avoids a hard-to-detect bad state.)
            if (InstantKt.isInfinite(packet.receiveTime) ||
                InstantKt.isInfinite(packet.sentPacket.sendTime))
            {
                continue;
            }
            // Insert the new packet.
            window.add(packet);
            window.get(window.size() - 1).sentPacket.priorUnackedData =
                window.get(window.size() - 1).sentPacket.priorUnackedData.times(
                    settings.unackedWeight);
            // In most cases, receive timestamps should already be in order, but in the
            // rare case where feedback packets have been reordered, we do some swaps to
            // ensure that the window is sorted.
            int i = window.size() - 1;
            while (i > 0 && window.get(i).receiveTime.isBefore(window.get(i - 1).receiveTime))
            {
                Collections.swap(window, i, i - 1);
                i--;
            }
            final Duration kMaxReorderingTime = Duration.ofSeconds(1);
            Duration receiveDelta =
                Duration.between(packet.receiveTime, window.get(window.size() - 1).receiveTime);

            if (receiveDelta.compareTo(kMaxReorderingTime) > 0)
            {
                logger.warn("Severe packet re-ordering or timestamps offset changed: " + receiveDelta);
                window.clear();
                latestDiscardedSendTime = Instant.MIN;
            }
        }

        // Remove old packets.
        while (firstPacketOutsideWindow())
        {
            latestDiscardedSendTime =
                InstantKt.max(latestDiscardedSendTime, window.get(0).sentPacket.sendTime);
            window.remove(0);
        }
    }

    @Override
    public Bandwidth bitrate()
    {
        if (window.isEmpty() || window.size() < settings.requiredPackets)
        {
            return null;
        }

        Duration largestRecvGap = Duration.ZERO;
        Duration secondLargestRecvGap = Duration.ZERO;
        for (int i = 1; i < window.size(); i++)
        {
            // Find receive time gaps.
            Duration gap = Duration.between(window.get(i - 1).receiveTime, window.get(i).receiveTime);
            if (gap.compareTo(largestRecvGap) > 0)
            {
                secondLargestRecvGap = largestRecvGap;
                largestRecvGap = gap;
            }
            else if (gap.compareTo(secondLargestRecvGap) > 0)
            {
                secondLargestRecvGap = gap;
            }
        }

        Instant firstSendTime = Instant.MAX;
        Instant lastSendTime = Instant.MIN;
        Instant firstRecvTime = Instant.MAX;
        Instant lastRecvTime = Instant.MIN;
        DataSize recvSize = DataSize.ofBytes(0);
        DataSize sendSize = DataSize.ofBytes(0);
        DataSize firstRecvSize = DataSize.ofBytes(0);
        DataSize lastSendSize = DataSize.ofBytes(0);
        int numSentPacketsInWindow = 0;
        for (PacketResult packet : window)
        {
            if (packet.receiveTime.isBefore(firstRecvTime))
            {
                firstRecvTime = packet.receiveTime;
                firstRecvSize =
                    packet.sentPacket.size.plus(packet.sentPacket.priorUnackedData);
            }
            lastRecvTime = InstantKt.max(lastRecvTime, packet.receiveTime);
            recvSize = recvSize.plus(packet.sentPacket.size);
            recvSize = recvSize.plus(packet.sentPacket.priorUnackedData);

            if (packet.sentPacket.sendTime.isBefore(latestDiscardedSendTime))
            {
                // If we have dropped packets from the window that were sent after
                // this packet, then this packet was reordered. Ignore it from
                // the send rate computation (since the send time may be very far
                // in the past, leading to underestimation of the send rate.)
                // However, ignoring packets creates a risk that we end up without
                // any packets left to compute a send rate.
                continue;
            }
            if (packet.sentPacket.sendTime.isAfter(lastSendTime))
            {
                lastSendTime = packet.sentPacket.sendTime;
                lastSendSize =
                    packet.sentPacket.size.plus(packet.sentPacket.priorUnackedData);
            }
            firstSendTime = InstantKt.min(firstSendTime, packet.sentPacket.sendTime);

            sendSize = sendSize.plus(packet.sentPacket.size);
            sendSize = sendSize.plus(packet.sentPacket.priorUnackedData);
            ++numSentPacketsInWindow;
        }

        // Suppose a packet of size S is sent every T milliseconds.
        // A window of N packets would contain N*S bytes, but the time difference
        // between the first and the last packet would only be (N-1)*T. Thus, we
        // need to remove the size of one packet to get the correct rate of S/T.
        // Which packet to remove (if the packets have varying sizes),
        // depends on the network model.
        // Suppose that 2 packets with sizes s1 and s2, are received at times t1
        // and t2, respectively. If the packets were transmitted back to back over
        // a bottleneck with rate capacity r, then we'd expect t2 = t1 + r * s2.
        // Thus, r = (t2-t1) / s2, so the size of the first packet doesn't affect
        // the difference between t1 and t2.
        // Analoguously, if the first packet is sent at time t1 and the sender
        // paces the packets at rate r, then the second packet can be sent at time
        // t2 = t1 + r * s1. Thus, the send rate estimate r = (t2-t1) / s1 doesn't
        // depend on the size of the last packet.
        recvSize = recvSize.minus(firstRecvSize);
        sendSize = sendSize.minus(lastSendSize);

        // Remove the largest gap by replacing it by the second largest gap.
        // This is to ensure that spurious "delay spikes" (i.e. when the
        // network stops transmitting packets for a short period, followed
        // by a burst of delayed packets), don't cause the estimate to drop.
        // This could cause an overestimation, which we guard against by
        // never returning an estimate above the send rate.
        if (!InstantKt.isFinite(firstRecvTime) || !InstantKt.isFinite(lastRecvTime))
        {
            throw new IllegalStateException("recv times must be finite");
        }
        Duration recvDuration = Duration.between(firstRecvTime, lastRecvTime)
            .minus(largestRecvGap).plus(secondLargestRecvGap);
        if (recvDuration.compareTo(Duration.ofMillis(1)) < 0)
        {
            recvDuration = Duration.ofMillis(1);
        }

        if (numSentPacketsInWindow < settings.requiredPackets)
        {
            // Too few send times to calculate a reliable send rate.
            return recvSize.per(recvDuration);
        }

        if (!InstantKt.isFinite(firstSendTime) || !InstantKt.isFinite(lastSendTime))
        {
            throw new IllegalStateException("send times must be finite");
        }
        Duration sendDuration = Duration.between(firstSendTime, lastSendTime);
        if (sendDuration.compareTo(Duration.ofMillis(1)) < 0)
        {
            sendDuration = Duration.ofMillis(1);
        }

        return Bandwidth.min(sendSize.per(sendDuration), recvSize.per(recvDuration));
    }

    @Override
    public Bandwidth peekRate()
    {
        return bitrate();
    }

    @Override
    public void setAlr(boolean inAlr)
    {
    }

    @Override
    public void setAlrEndedTime(Instant alrEndedTime)
    {
    }

    private boolean firstPacketOutsideWindow()
    {
        if (window.isEmpty())
        {
            return false;
        }
        if (window.size() > settings.maxWindowPackets)
        {
            return true;
        }
        Duration currentWindowDuration =
            Duration.between(window.get(0).receiveTime, window.get(window.size() - 1).receiveTime);
        if (currentWindowDuration.compareTo(settings.maxWindowDuration) > 0)
        {
            return true;
        }
        if (window.size() > settings.windowPackets &&
            currentWindowDuration.compareTo(settings.minWindowDuration) > 0)
        {
            return true;
        }
        return false;
    }

    /* Upstream has this as a file-level Collection<E>.isSorted extension with a
     * TODO to move it to a util; kept private here until another caller needs it. */
    private static <E> boolean isSorted(List<E> list, Comparator<E> comparator)
    {
        for (int i = 1; i < list.size(); i++)
        {
            if (comparator.compare(list.get(i - 1), list.get(i)) > 0)
            {
                return false;
            }
        }
        return true;
    }
}
