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

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket;
import org.jitsi.rtp.util.BufferPool;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.DurationKt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This class is a port of CongestionControlFeedback in
 * congestion_control_feedback.h/congestion_control_feedback.cc in Chrome
 * https://source.chromium.org/chromium/chromium/src/+/main:third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/congestion_control_feedback.h;rcl=eda3abcd0f44ed2e4c36586d35bdc06885abd25c
 *
 * Because of this, it explicitly does NOT try to conform
 * to Kotlin style or idioms, instead striving to match the
 * Chrome code as closely as possible in an effort to make
 * future updates easier.
 */
public class RtcpFbCcfbPacketBuilder
{
    private RtcpHeaderBuilder rtcpHeader = new RtcpHeaderBuilder();
    private long mediaSourceSsrc = -1;
    // `Packets` MUST be sorted in sequence_number order per SSRC. There MUST not
    // be missing sequence numbers between `Packets`. `Packets` MUST not include
    // duplicate sequence numbers.
    private List<PacketInfo> packets = Collections.emptyList();
    private final long reportTimestampCompactNtp;

    private final Map<Long, List<PacketInfo>> groupedPackets;

    public RtcpFbCcfbPacketBuilder(long reportTimestampCompactNtp)
    {
        this.reportTimestampCompactNtp = reportTimestampCompactNtp;
        this.groupedPackets = groupBySsrc(packets);
    }

    public RtcpFbCcfbPacketBuilder(
        RtcpHeaderBuilder rtcpHeader,
        long mediaSourceSsrc,
        List<PacketInfo> packets,
        long reportTimestampCompactNtp)
    {
        this.rtcpHeader = rtcpHeader;
        this.mediaSourceSsrc = mediaSourceSsrc;
        this.packets = packets;
        this.reportTimestampCompactNtp = reportTimestampCompactNtp;
        this.groupedPackets = groupBySsrc(packets);
    }

    private static Map<Long, List<PacketInfo>> groupBySsrc(List<PacketInfo> packets)
    {
        Map<Long, List<PacketInfo>> result = new LinkedHashMap<>();
        for (PacketInfo packet : packets)
        {
            result.computeIfAbsent(packet.getSsrc(), k -> new ArrayList<>()).add(packet);
        }
        return result;
    }

    public RtcpHeaderBuilder getRtcpHeader()
    {
        return rtcpHeader;
    }

    public long getMediaSourceSsrc()
    {
        return mediaSourceSsrc;
    }

    public void setMediaSourceSsrc(long mediaSourceSsrc)
    {
        this.mediaSourceSsrc = mediaSourceSsrc;
    }

    public List<PacketInfo> getPackets()
    {
        return packets;
    }

    public long getReportTimestampCompactNtp()
    {
        return reportTimestampCompactNtp;
    }

    private int blockLength()
    {
        int totalSize = RtcpFbPacket.HEADER_SIZE + RtcpFbCcfbPacket.kTimestampLength;

        for (List<PacketInfo> packetsForSsrc : groupedPackets.values())
        {
            int blockSize = packetsForSsrc.size() * 2;
            totalSize += RtcpFbCcfbPacket.kHeaderPerMediaSsrcLength + blockSize + RtpUtils.getNumPaddingBytes(blockSize);
        }

        return totalSize;
    }

    public void writeTo(byte[] buf, int offset)
    {
        int blockLength = blockLength();
        assert blockLength % 4 == 0; // Will never need padding
        int packetSize = blockLength;
        if (buf.length - offset < packetSize)
        {
            throw new IllegalArgumentException(
                "Buffer of size " + buf.length + " with offset " + offset +
                    " too small for CCFB packet of size " + packetSize
            );
        }
        rtcpHeader.setPacketType(TransportLayerRtcpFbPacket.PT)
            .setReportCount(RtcpFbCcfbPacket.FMT)
            .setLength(RtpUtils.calculateRtcpLengthFieldValue(blockLength));
        rtcpHeader.writeTo(buf, offset);

        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc);
        int position = offset + RtcpFbPacket.HEADER_SIZE;

        for (Map.Entry<Long, List<PacketInfo>> entry : groupedPackets.entrySet())
        {
            long ssrc = entry.getKey();
            List<PacketInfo> packetsForSsrc = entry.getValue();
            if (packetsForSsrc.size() > 16384)
            {
                throw new IllegalStateException("Unexpected number of reports: " + packetsForSsrc.size());
            }

            ByteArrayExtensions.putInt(buf, position, (int) ssrc);
            position += 4;

            int beginSeq = packetsForSsrc.get(0).getSequenceNumber();
            int numReports = packetsForSsrc.size();

            ByteArrayExtensions.putShort(buf, position, (short) beginSeq);
            position += 2;
            ByteArrayExtensions.putShort(buf, position, (short) numReports);
            position += 2;

            for (int i = 0; i < packetsForSsrc.size(); i++)
            {
                PacketInfo packet = packetsForSsrc.get(i);
                int expectedSeq = RtpUtils.applySequenceNumberDelta(beginSeq, i);
                if (packet.getSequenceNumber() != expectedSeq)
                {
                    throw new IllegalStateException(
                        "Sequence number for report " + i + " of " + ssrc + " is wrong " +
                            "(expected " + expectedSeq + " == " + beginSeq + " + " + i +
                            ", got " + packet.getSequenceNumber() + ")"
                    );
                }
                int packetInfo;
                if (packet instanceof ReceivedPacketInfo)
                {
                    ReceivedPacketInfo received = (ReceivedPacketInfo) packet;
                    packetInfo = 0x8000 | to2BitEcn(received.getEcn()) | to13BitAto(received.getArrivalTimeOffset());
                }
                else
                {
                    packetInfo = 0;
                }
                ByteArrayExtensions.putShort(buf, position, (short) packetInfo);
                position += 2;
            }
            int numPaddingBytes = RtpUtils.getNumPaddingBytes(2 * numReports);
            for (int i = 0; i < numPaddingBytes; i++)
            {
                buf[position++] = 0x00;
            }
        }

        ByteArrayExtensions.putInt(buf, position, (int) reportTimestampCompactNtp);
        position += 4;
        assert position - offset == packetSize;
    }

    public RtcpFbCcfbPacket build()
    {
        int blockLength = blockLength();
        int packetSize = blockLength + RtpUtils.getNumPaddingBytes(blockLength);
        byte[] buf = BufferPool.getArray(packetSize);
        writeTo(buf, 0);
        return new RtcpFbCcfbPacket(buf, 0, packetSize);
    }

    // Arrival time offset (ATO, 13 bits):
    // The arrival time of the RTP packet at the receiver, as an offset before the
    // time represented by the Report Timestamp (RTS) field of this RTCP congestion
    // control feedback report. The ATO field is in units of 1/1024 seconds (this
    // unit is chosen to give exact offsets from the RTS field) so, for example, an
    // ATO value of 512 indicates that the corresponding RTP packet arrived exactly
    // half a second before the time instant represented by the RTS field. If the
    // measured value is greater than 8189/1024 seconds (the value that would be
    // coded as 0x1FFD), the value 0x1FFE MUST be reported to indicate an over-range
    // measurement. If the measurement is unavailable or if the arrival time of the
    // RTP packet is after the time represented by the RTS field, then an ATO value
    // of 0x1FFF MUST be reported for the packet.
    private static int to13BitAto(Duration arrivalTimeOffset)
    {
        if (arrivalTimeOffset.isNegative())
        {
            return 0x1FFF;
        }
        return Math.min((int) (1024 * DurationKt.toDouble(arrivalTimeOffset)), 0x1FFE);
    }

    private static int to2BitEcn(EcnMarking ecnMarking)
    {
        return ((int) ecnMarking.getBits()) << 13;
    }
}
