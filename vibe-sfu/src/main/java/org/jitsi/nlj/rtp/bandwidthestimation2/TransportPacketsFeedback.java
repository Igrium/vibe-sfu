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
import org.jitsi.utils.InstantKt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Summary of transport packets feedback.
 *
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class TransportPacketsFeedback
{
    public Instant feedbackTime = InstantKt.NEVER;
    public DataSize dataInFlight = DataSize.ZERO;
    public List<PacketResult> packetFeedbacks = new ArrayList<>();
    public boolean transportSupportsEcn = false;

    /** Arrival times for messages without send times information */
    public final List<Instant> sendlessArrivalTimes = new ArrayList<>();

    public List<PacketResult> receivedWithSendInfo()
    {
        return packetFeedbacks.stream().filter(PacketResult::isReceived).collect(Collectors.toList());
    }

    public List<PacketResult> lostWithSendInfo()
    {
        return packetFeedbacks.stream().filter(pr -> !pr.isReceived()).collect(Collectors.toList());
    }

    public List<PacketResult> packetsWithFeedback()
    {
        return packetFeedbacks;
    }

    public List<PacketResult> sortedByReceiveTime()
    {
        return receivedWithSendInfo().stream()
            .sorted((a, b) -> a.receiveTime.compareTo(b.receiveTime))
            .collect(Collectors.toList());
    }
}
