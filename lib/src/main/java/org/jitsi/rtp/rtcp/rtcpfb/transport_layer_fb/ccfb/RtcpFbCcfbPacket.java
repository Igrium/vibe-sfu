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

import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket;
import org.jitsi.rtp.util.FieldParsers;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.DurationKt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is a port of CongestionControlFeedback in
 * congestion_control_feedback.h/congestion_control_feedback.cc in Chrome
 * https://source.chromium.org/chromium/chromium/src/+/main:third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/congestion_control_feedback.h;rcl=eda3abcd0f44ed2e4c36586d35bdc06885abd25c
 *
 * Because of this, it explicitly does NOT try to conform
 * to Kotlin style or idioms, instead striving to match the
 * Chrome code as closely as possible in an effort to make
 * future updates easier.
 *
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |V=2|P| FMT=11  |   PT = 205    |          length               |
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |                 SSRC of RTCP packet sender                    |
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |                   SSRC of 1st RTP Stream                      |
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |          begin_seq            |          num_reports          |
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |R|ECN|  Arrival time offset    | ...                           .
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      .                                                               .
 *      .                                                               .
 *      .                                                               .
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |                   SSRC of nth RTP Stream                      |
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |          begin_seq            |          num_reports          |
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |R|ECN|  Arrival time offset    | ...                           |
 *      .                                                               .
 *      .                                                               .
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |                 Report Timestamp (32 bits)                    |
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class RtcpFbCcfbPacket extends TransportLayerRtcpFbPacket
{
    public static final int FMT = 11;

    static final int kHeaderPerMediaSsrcLength = 8;
    static final int kTimestampLength = 4;

    /**
     * Because much of time this packet is one that we built (not one
     * that came in from the network) we don't care about re-parsing all
     * of these fields.  To avoid doing this work, we put them in this
     * class and make its initialization lazy: they'll only be parsed
     * if we access them (which we do for packets that are received from
     * the network but not for ones we send out).
     */
    private static final class CcfbData
    {
        private final List<PacketInfo> packets;
        private final long reportTimestampCompactNtp;

        private CcfbData(List<PacketInfo> packets, long reportTimestampCompactNtp)
        {
            this.packets = packets;
            this.reportTimestampCompactNtp = reportTimestampCompactNtp;
        }
    }

    private CcfbData data;

    public RtcpFbCcfbPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
    }

    private CcfbData getData()
    {
        if (data == null)
        {
            List<PacketInfo> packets = new ArrayList<>();

            if (getPacketLength() - FCI_OFFSET < kTimestampLength)
            {
                throw new IllegalArgumentException("CCFB packet is too small for timestamp");
            }
            long reportTimestampCompactNtp = FieldParsers.getIntAsLong(buffer, getPacketLength() - kTimestampLength);

            int position = offset + FCI_OFFSET;
            int end = offset + getPacketLength() - kTimestampLength;

            while (position + kHeaderPerMediaSsrcLength < end)
            {
                long ssrc = FieldParsers.getIntAsLong(buffer, position);
                position += 4;
                int baseSeqno = FieldParsers.getShortAsInt(buffer, position);
                position += 2;
                int numReports = FieldParsers.getShortAsInt(buffer, position);
                position += 2;

                if (position + 2 * numReports > end)
                {
                    throw new IllegalArgumentException("Reports would extend past end of CCFB packet");
                }

                for (int i = 0; i < numReports; i++)
                {
                    int packetInfo = FieldParsers.getShortAsInt(buffer, position);
                    position += 2;
                    int seqNo = RtpUtils.applySequenceNumberDelta(baseSeqno, i);
                    boolean received = (packetInfo & 0x8000) != 0;
                    if (received)
                    {
                        packets.add(
                            new ReceivedPacketInfo(ssrc, seqNo, atoToTimeDelta(packetInfo), toEcnMarking(packetInfo))
                        );
                    }
                    else
                    {
                        packets.add(new UnreceivedPacketInfo(ssrc, seqNo));
                    }
                }
                if ((numReports % 2) != 0)
                {
                    // 2 bytes padding
                    position += 2;
                }
            }
            if (position != end)
            {
                throw new IllegalArgumentException("CCFB packet size was incorrect");
            }

            data = new CcfbData(packets, reportTimestampCompactNtp);
        }
        return data;
    }

    public List<PacketInfo> getPackets()
    {
        return getData().packets;
    }

    public long getReportTimestampCompactNtp()
    {
        return getData().reportTimestampCompactNtp;
    }

    @Override
    public RtcpFbCcfbPacket clone()
    {
        return new RtcpFbCcfbPacket(cloneBuffer(0), 0, length);
    }

    private static Duration atoToTimeDelta(int receiveInfo)
    {
        int ato = receiveInfo & 0x1FFF;
        if (ato == 0x1FFE)
        {
            return DurationKt.getMAX_DURATION();
        }
        if (ato == 0x1FFF)
        {
            return DurationKt.getMIN_DURATION();
        }
        return DurationKt.div(DurationKt.getSecs(ato), 1024L);
    }

    private static EcnMarking toEcnMarking(int receiveInfo)
    {
        return EcnMarking.fromBits((byte) ((receiveInfo >> 13) & 0b11));
    }
}
