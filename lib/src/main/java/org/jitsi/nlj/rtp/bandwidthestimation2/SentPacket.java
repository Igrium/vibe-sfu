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

/**
 * A sent packet.
 *
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class SentPacket
{
    public Instant sendTime = InstantKt.NEVER;
    /** Size of packet with overhead up to IP layer. */
    public DataSize size = DataSize.ZERO;
    /** Size of preceeding packets that are not part of feedback */
    public DataSize priorUnackedData = DataSize.ZERO;
    /** Probe cluster id and parameters including bitrate, number of packets and
     number of bytes. */
    public PacedPacketInfo pacingInfo = new PacedPacketInfo();
    /** True if the packet is an audio packet, false for video, padding, RTX, etc. */
    public boolean audio = false;
    /** Transport independent sequence number, any tracked packet should have a
     sequence number that is unique over the whole call and increasing by 1 for
     each packet. */
    public long sequenceNumber = 0;
    /** Tracked data in flight when the packet was sent, excluding unacked data. */
    public DataSize dataInFlight = DataSize.ZERO;

    public SentPacket copy()
    {
        SentPacket c = new SentPacket();
        c.sendTime = sendTime;
        c.size = size;
        c.priorUnackedData = priorUnackedData;
        c.pacingInfo = pacingInfo;
        c.audio = audio;
        c.sequenceNumber = sequenceNumber;
        c.dataInFlight = dataInFlight;
        return c;
    }
}
