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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb;

import org.jitsi.utils.DurationKt;

import java.time.Duration;

public class ReceivedPacketInfo extends PacketInfo
{
    private final Duration arrivalTimeOffset;
    private final EcnMarking ecn;

    public ReceivedPacketInfo(long ssrc, int sequenceNumber, Duration arrivalTimeOffset, EcnMarking ecn)
    {
        super(ssrc, sequenceNumber);
        this.arrivalTimeOffset = arrivalTimeOffset;
        this.ecn = ecn;
    }

    public ReceivedPacketInfo(long ssrc, int sequenceNumber)
    {
        this(ssrc, sequenceNumber, DurationKt.getMIN_DURATION(), EcnMarking.kNotEct);
    }

    public ReceivedPacketInfo()
    {
        this(0, 0);
    }

    public Duration getArrivalTimeOffset()
    {
        return arrivalTimeOffset;
    }

    public EcnMarking getEcn()
    {
        return ecn;
    }

    @Override
    public String toString()
    {
        return "Received: " + super.toString() + ", arrivalTimeOffset=" + arrivalTimeOffset + ", ecn=" + ecn;
    }
}
