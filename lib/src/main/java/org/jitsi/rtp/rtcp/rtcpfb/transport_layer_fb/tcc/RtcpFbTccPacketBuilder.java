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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket;
import org.jitsi.rtp.rtp.RtpSequenceNumber;
import org.jitsi.rtp.util.BufferPool;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.InstantKt;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is a port of TransportFeedback in
 * transport_feedback.h/transport_feedback.cc in Chrome
 * https://cs.chromium.org/chromium/src/third_party/webrtc/modules/rtp_rtcp/source/rtcp_packet/transport_feedback.h?l=95&rcl=20393ee9b7ba622f254908646a9c31bf87349fc7
 *
 * Because of this, it explicitly does NOT try to conform
 * to Kotlin style or idioms, instead striving to match the
 * Chrome code as closely as possible in an effort to make
 * future updates easier.
 */
@SuppressFBWarnings(
    value = "NM_METHOD_NAMING_CONVENTION",
    justification = "This class is a port and use the original names."
)
public class RtcpFbTccPacketBuilder
{
    private RtcpHeaderBuilder rtcpHeader = new RtcpHeaderBuilder();
    private long mediaSourceSsrc = -1;
    private final int feedbackPacketSeqNum;

    private RtpSequenceNumber base_seq_no_ = RtpSequenceNumber.INVALID;

    // The reference time, in ticks.  Chrome passes this into BuildFeedbackPacket, but we don't
    // hold the times in the same way, so we'll just assign it the first time we see
    // a packet in AddReceivedPacket
    private long base_time_ticks_ = -1;

    // The amount of packets_ whose status are represented
    private int num_seq_no_ = 0;

    // The current chunk we're 'filling out' as packets
    // are received
    private final LastChunk last_chunk_ = new LastChunk();

    // All but last encoded packet chunks.
    private final List<Integer> encoded_chunks_ = new ArrayList<>();

    // The size of the entire packet, in bytes
    private int size_bytes_ = RtcpFbTccPacket.kTransportFeedbackHeaderSizeBytes;
    private Instant last_timestamp_ = Instant.EPOCH;
    private final List<PacketReport> packets_ = new ArrayList<>();

    public RtcpFbTccPacketBuilder()
    {
        this.feedbackPacketSeqNum = -1;
    }

    public RtcpFbTccPacketBuilder(RtcpHeaderBuilder rtcpHeader, long mediaSourceSsrc, int feedbackPacketSeqNum)
    {
        this.rtcpHeader = rtcpHeader;
        this.mediaSourceSsrc = mediaSourceSsrc;
        this.feedbackPacketSeqNum = feedbackPacketSeqNum;
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

    public int getFeedbackPacketSeqNum()
    {
        return feedbackPacketSeqNum;
    }

    public RtpSequenceNumber getBase_seq_no_()
    {
        return base_seq_no_;
    }

    public int getNum_seq_no_()
    {
        return num_seq_no_;
    }

    public void SetBase(int base_sequence, Instant ref_timestamp)
    {
        base_seq_no_ = RtpSequenceNumber.toRtpSequenceNumber(base_sequence);
        base_time_ticks_ =
            (InstantKt.toEpochMicro(ref_timestamp) % DurationKt.toMicros(RtcpFbTccPacket.kTimeWrapPeriod))
                / DurationKt.toMicros(RtcpFbTccPacket.kBaseScaleFactor);
        last_timestamp_ = BaseTime();
    }

    public boolean AddReceivedPacket(int seqNum, Instant timestampIn)
    {
        RtpSequenceNumber sequence_number = RtpSequenceNumber.toRtpSequenceNumber(seqNum);
        Instant timestamp = timestampIn;
        if (last_timestamp_.isAfter(timestamp))
        {
            timestamp = timestamp.plus(
                DurationKt.roundUpTo(Duration.between(timestamp, last_timestamp_), RtcpFbTccPacket.kTimeWrapPeriod)
            );
        }
        long delta_full =
            DurationKt.toMicros(Duration.between(last_timestamp_, timestamp)) % DurationKt.toMicros(RtcpFbTccPacket.kTimeWrapPeriod);
        if (delta_full > DurationKt.toMicros(RtcpFbTccPacket.kTimeWrapPeriod) / 2)
        {
            delta_full -= DurationKt.toMicros(RtcpFbTccPacket.kTimeWrapPeriod);
            delta_full -= DurationKt.toMicros(RtcpFbTccPacket.kDeltaScaleFactor) / 2;
        }
        else
        {
            delta_full += DurationKt.toMicros(RtcpFbTccPacket.kDeltaScaleFactor) / 2;
        }
        delta_full /= DurationKt.toMicros(RtcpFbTccPacket.kDeltaScaleFactor);

        short delta = (short) delta_full;
        // If larger than 16bit signed, we can't represent it - need new fb packet.
        if ((long) delta != delta_full)
        {
            return false;
        }
        RtpSequenceNumber next_seq_no = base_seq_no_.plus(num_seq_no_);
        if (!sequence_number.equals(next_seq_no))
        {
            RtpSequenceNumber lastSeqNo = next_seq_no.minus(1);
            if (sequence_number.compareTo(lastSeqNo) <= 0)
            {
                return false;
            }
            while (!next_seq_no.equals(sequence_number))
            {
                if (!AddDeltaSize(0))
                {
                    return false;
                }
                next_seq_no = next_seq_no.plus(1);
            }
        }
        int delta_size = (delta >= 0 && delta <= 0xff) ? 1 : 2;
        if (!AddDeltaSize(delta_size))
        {
            return false;
        }

        packets_.add(new ReceivedPacketReport(sequence_number.getValue(), delta));
        last_timestamp_ = last_timestamp_.plus(DurationKt.times((int) delta, RtcpFbTccPacket.kDeltaScaleFactor));
        size_bytes_ += delta_size;

        return true;
    }

    public Instant BaseTime()
    {
        return Instant.EPOCH.plus(DurationKt.times(base_time_ticks_, RtcpFbTccPacket.kBaseScaleFactor));
    }

    private boolean AddDeltaSize(int deltaSize)
    {
        if (num_seq_no_ == RtcpFbTccPacket.kMaxReportedPackets)
        {
            return false;
        }
        int add_chunk_size = last_chunk_.Empty() ? RtcpFbTccPacket.kChunkSizeBytes : 0;

        if (size_bytes_ + deltaSize + add_chunk_size > RtcpFbTccPacket.kMaxSizeBytes)
        {
            return false;
        }

        if (last_chunk_.CanAdd(deltaSize))
        {
            size_bytes_ += add_chunk_size;
            last_chunk_.Add(deltaSize);
            ++num_seq_no_;
            return true;
        }

        if (size_bytes_ + deltaSize + RtcpFbTccPacket.kChunkSizeBytes > RtcpFbTccPacket.kMaxSizeBytes)
        {
            return false;
        }

        encoded_chunks_.add(last_chunk_.Emit());
        size_bytes_ += RtcpFbTccPacket.kChunkSizeBytes;
        last_chunk_.Add(deltaSize);
        ++num_seq_no_;
        return true;
    }

    public RtcpFbTccPacket build()
    {
        int packetSize = size_bytes_ + RtpUtils.getNumPaddingBytes(size_bytes_);
        byte[] buf = BufferPool.getArray(packetSize);
        writeTo(buf, 0);
        return new RtcpFbTccPacket(buf, 0, packetSize);
    }

    public void writeTo(byte[] buf, int offset)
    {
        // NOTE: padding is held 'internally' in the TCC FCI, so we don't set
        // the padding bit on the header
        int paddingBytes = RtpUtils.getNumPaddingBytes(size_bytes_);
        rtcpHeader.setPacketType(TransportLayerRtcpFbPacket.PT)
            .setReportCount(RtcpFbTccPacket.FMT)
            .setLength(RtpUtils.calculateRtcpLengthFieldValue(size_bytes_ + paddingBytes));
        rtcpHeader.writeTo(buf, offset);

        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc);
        RtcpFbTccPacket.setBaseSeqNum(buf, offset, base_seq_no_.getValue());
        RtcpFbTccPacket.setPacketStatusCount(buf, offset, num_seq_no_);
        RtcpFbTccPacket.setReferenceTimeTicks(buf, offset, (int) base_time_ticks_);
        RtcpFbTccPacket.setFeedbackPacketCount(buf, offset, feedbackPacketSeqNum);

        int currOffset = RtcpFbTccPacket.PACKET_CHUNKS_OFFSET;
        for (int chunk : encoded_chunks_)
        {
            ByteArrayExtensions.putShort(buf, currOffset, (short) chunk);
            currOffset += RtcpFbTccPacket.kChunkSizeBytes;
        }
        if (!last_chunk_.Empty())
        {
            int chunk = last_chunk_.EncodeLast();
            ByteArrayExtensions.putShort(buf, currOffset, (short) chunk);
            currOffset += RtcpFbTccPacket.kChunkSizeBytes;
        }
        for (PacketReport report : packets_)
        {
            if (report instanceof ReceivedPacketReport)
            {
                short deltaTicks = ((ReceivedPacketReport) report).getDeltaTicks();
                if (deltaTicks >= 0 && deltaTicks <= 0xFF)
                {
                    buf[currOffset++] = (byte) deltaTicks;
                }
                else
                {
                    ByteArrayExtensions.putShort(buf, currOffset, deltaTicks);
                    currOffset += 2;
                }
            }
        }
        for (int i = 0; i < paddingBytes; i++)
        {
            buf[currOffset++] = 0x00;
        }
    }

    public void clear()
    {
        num_seq_no_ = 0;
        size_bytes_ = RtcpFbTccPacket.kTransportFeedbackHeaderSizeBytes;
    }
}
