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

import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking;
import org.jitsi.utils.InstantKt;

import java.time.Instant;

/**
 * The result of packet feedback.
 *
 * Common network types used for bandwidth estimation, based on WebRTC
 * api/transport/network_types.{h,cc} in WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class PacketResult
{
    public SentPacket sentPacket = new SentPacket();
    public Instant receiveTime = InstantKt.NEVER;
    public EcnMarking ecn = EcnMarking.kNotEct;
    // `rtp_packet_info` is only set if the feedback is related to a RTP packet.
    public RtpPacketInfo rtpPacketInfo = null;
    // Jitsi extension: Whether a packet was previously reported lost
    public boolean previouslyReportedLost = false;

    public static class RtpPacketInfo
    {
        public final long ssrc;
        public final int rtpSequenceNumber;
        public final boolean isRetransmission;

        public RtpPacketInfo(long ssrc, int rtpSequenceNumber, boolean isRetransmission)
        {
            this.ssrc = ssrc;
            this.rtpSequenceNumber = rtpSequenceNumber;
            this.isRetransmission = isRetransmission;
        }

        public RtpPacketInfo()
        {
            this(0, 0, false);
        }
    }

    public boolean isReceived()
    {
        return InstantKt.isFinite(receiveTime);
    }

    public PacketResult copy()
    {
        PacketResult c = new PacketResult();
        c.sentPacket = sentPacket.copy();
        c.receiveTime = receiveTime;
        c.ecn = ecn;
        c.rtpPacketInfo = rtpPacketInfo;
        c.previouslyReportedLost = previouslyReportedLost;
        return c;
    }
}
