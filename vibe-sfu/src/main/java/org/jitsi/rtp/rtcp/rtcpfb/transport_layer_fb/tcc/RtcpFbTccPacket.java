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
import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket;
import org.jitsi.rtp.rtp.RtpSequenceNumber;
import org.jitsi.rtp.util.FieldParsers;
import org.jitsi.utils.DurationKt;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * NOTE(brian): This class is a port of the rest of the logic in TransportFeedback
 * not covered by RtcpFbTccPacketBuilder.  Chrome uses a single class for both
 * the 'builder' and the 'parser' but because of the way we define packets
 * (inheriting from the buffer type and therefore always requiring a valid buffer),
 * we separate builders out into their own class.  Because of that, this class
 * and RtcpFbTccPacketBuilder have overlap in their members.
 *
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|  FMT=15 |    PT=205     |           length              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     SSRC of packet sender                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      SSRC of media source                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |      base sequence number     |      packet status count      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                 reference time                | fb pkt. count |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          packet chunk         |         packet chunk          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         packet chunk          |  recv delta   |  recv delta   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           recv delta          |  recv delta   | zero padding  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * packet status count:  16 bits The number of packets this feedback
 *  contains status for, starting with the packet identified
 *  by the base sequence number.
 *
 * feedback packet count:  8 bits A counter incremented by one for each
 *  feedback packet sent.  Used to detect feedback packet
 *  losses.
 */
@SuppressFBWarnings(
    value = "NM_METHOD_NAMING_CONVENTION",
    justification = "This class is a port and use the original names."
)
public class RtcpFbTccPacket extends TransportLayerRtcpFbPacket implements Iterable<PacketReport>
{
    public static final int FMT = 15;

    // Convert to multiples of 0.25ms
    public static final Duration kDeltaScaleFactor = DurationKt.getMicros(250);

    // Maximum number of packets_ (including missing) TransportFeedback can report.
    public static final int kMaxReportedPackets = 0xFFFF;
    public static final int kChunkSizeBytes = 2;

    // Fit TCC packets within an MTU and allow for further encapsulation (and perhaps compound RTCP)
    public static final int kMaxSizeBytes = 1200;

    // Header size:
    // * 4 bytes Common RTCP Packet Header
    // * 8 bytes Common Packet Format for RTCP Feedback Messages
    // * 8 bytes FeedbackPacket header
    public static final int kTransportFeedbackHeaderSizeBytes = 4 + 8 + 8;

    // Used to convert from microseconds to multiples of 64ms
    public static final Duration kBaseScaleFactor = DurationKt.times(kDeltaScaleFactor, 1 << 8);

    // The reference time field is 24 bits and are represented as multiples of 64ms
    // When the reference time field would need to wrap around
    public static final Duration kTimeWrapPeriod = DurationKt.times((long) (1 << 24), kBaseScaleFactor);

    public static final int BASE_SEQ_NUM_OFFSET = RtcpFbPacket.HEADER_SIZE;
    public static final int PACKET_STATUS_COUNT_OFFSET = RtcpFbPacket.HEADER_SIZE + 2;
    public static final int REFERENCE_TIME_OFFSET = RtcpFbPacket.HEADER_SIZE + 4;
    public static final int FB_PACKET_COUNT_OFFSET = RtcpFbPacket.HEADER_SIZE + 7;
    public static final int PACKET_CHUNKS_OFFSET = RtcpFbPacket.HEADER_SIZE + 8;

    // baseOffset in all of these refers to the start of the entire RTCP TCC packet
    public static int getBaseSeqNum(byte[] buf, int baseOffset)
    {
        return FieldParsers.getShortAsInt(buf, baseOffset + BASE_SEQ_NUM_OFFSET);
    }

    public static void setBaseSeqNum(byte[] buf, int baseOffset, int value)
    {
        ByteArrayExtensions.putShort(buf, baseOffset + BASE_SEQ_NUM_OFFSET, (short) value);
    }

    public static int getPacketStatusCount(byte[] buf, int baseOffset)
    {
        return FieldParsers.getShortAsInt(buf, baseOffset + PACKET_STATUS_COUNT_OFFSET);
    }

    public static void setPacketStatusCount(byte[] buf, int baseOffset, int value)
    {
        ByteArrayExtensions.putShort(buf, baseOffset + PACKET_STATUS_COUNT_OFFSET, (short) value);
    }

    public static long getReferenceTimeTicks(byte[] buf, int baseOffset)
    {
        return Unsigned.toPositiveLong(FieldParsers.get3BytesAsInt(buf, baseOffset + REFERENCE_TIME_OFFSET));
    }

    public static void setReferenceTimeTicks(byte[] buf, int baseOffset, int refTimeTicks)
    {
        ByteArrayExtensions.put3Bytes(buf, baseOffset + REFERENCE_TIME_OFFSET, refTimeTicks);
    }

    public static int getFeedbackPacketCount(byte[] buf, int baseOffset)
    {
        return FieldParsers.getByteAsInt(buf, baseOffset + FB_PACKET_COUNT_OFFSET);
    }

    public static void setFeedbackPacketCount(byte[] buf, int baseOffset, int value)
    {
        buf[baseOffset + FB_PACKET_COUNT_OFFSET] = (byte) value;
    }

    /**
     * Because much of time this packet is one that we built (not one
     * that came in from the network) we don't care about re-parsing all
     * of these fields.  To avoid doing this work, we put them in this
     * class and make its initialization lazy: they'll only be parsed
     * if we access them (which we do for packets that are received from
     * the network but not for ones we send out).
     */
    private static final class TccMemberData
    {
        private final int base_seq_no_;
        private long base_time_ticks_;
        private final List<Integer> encoded_chunks_;
        private LastChunk last_chunk_;
        private int num_seq_no_;
        private Instant last_timestamp_;
        private final List<PacketReport> packets_;

        private TccMemberData(
            int base_seq_no_,
            long base_time_ticks_,
            List<Integer> encoded_chunks_,
            LastChunk last_chunk_,
            int num_seq_no_,
            Instant last_timestamp_,
            List<PacketReport> packets_)
        {
            this.base_seq_no_ = base_seq_no_;
            this.base_time_ticks_ = base_time_ticks_;
            this.encoded_chunks_ = encoded_chunks_;
            this.last_chunk_ = last_chunk_;
            this.num_seq_no_ = num_seq_no_;
            this.last_timestamp_ = last_timestamp_;
            this.packets_ = packets_;
        }
    }

    private TccMemberData data;

    private final int feedbackSeqNum;

    public RtcpFbTccPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
        this.feedbackSeqNum = getFeedbackPacketCount(buffer, offset);
    }

    public int getFeedbackSeqNum()
    {
        return feedbackSeqNum;
    }

    private TccMemberData getData()
    {
        if (data == null)
        {
            RtpSequenceNumber base_seq_no_ = RtpSequenceNumber.toRtpSequenceNumber(getBaseSeqNum(buffer, offset));
            int status_count = getPacketStatusCount(buffer, offset);
            List<Integer> encoded_chunks_ = new ArrayList<>();
            LastChunk last_chunk_ = new LastChunk();
            int num_seq_no_;
            Instant last_timestamp_ = Instant.EPOCH;
            List<PacketReport> packets_ = new ArrayList<>();

            long base_time_ticks_ = getReferenceTimeTicks(buffer, offset);
            List<Integer> delta_sizes = new ArrayList<>();
            int index = offset + PACKET_CHUNKS_OFFSET;
            int end_index = offset + length;
            while (delta_sizes.size() < status_count)
            {
                if (index + kChunkSizeBytes > end_index)
                {
                    throw new RuntimeException("Buffer overflow while parsing packet.");
                }
                int chunk = FieldParsers.getShortAsInt(buffer, index);
                index += kChunkSizeBytes;
                encoded_chunks_.add(chunk);
                last_chunk_.Decode(chunk, status_count - delta_sizes.size());
                last_chunk_.AppendTo(delta_sizes);
            }
            // Last chunk is stored in the |last_chunk_|.
            if (!encoded_chunks_.isEmpty())
            {
                encoded_chunks_.remove(encoded_chunks_.size() - 1);
            }
            num_seq_no_ = status_count;

            RtpSequenceNumber seq_no = base_seq_no_;
            int recv_delta_size = 0;
            for (int delta_size : delta_sizes)
            {
                recv_delta_size += delta_size;
            }

            // Determine if timestamps, that is, recv_delta are included in the packet.
            if (end_index >= index + recv_delta_size)
            {
                for (int delta_size : delta_sizes)
                {
                    if (index + delta_size > end_index)
                    {
                        throw new RuntimeException("Buffer overflow while parsing packet.");
                    }
                    switch (delta_size)
                    {
                        case 0:
                            packets_.add(new UnreceivedPacketReport(seq_no.getValue()));
                            break;
                        case 1:
                        {
                            byte delta = buffer[index];
                            packets_.add(new ReceivedPacketReport(seq_no.getValue(), Unsigned.toPositiveShort(delta)));
                            last_timestamp_ = last_timestamp_.plus(DurationKt.times((int) delta, kDeltaScaleFactor));
                            index += delta_size;
                            break;
                        }
                        case 2:
                        {
                            int delta = FieldParsers.getShortAsInt(buffer, index);
                            packets_.add(new ReceivedPacketReport(seq_no.getValue(), (short) delta));
                            last_timestamp_ = last_timestamp_.plus(DurationKt.times(delta, kDeltaScaleFactor));
                            index += delta_size;
                            break;
                        }
                        case 3:
                            throw new RuntimeException("Warning: invalid delta size for seq_no " + seq_no);
                        default:
                            break;
                    }
                    seq_no = seq_no.plus(1);
                }
            }
            else
            {
                // The packet does not contain receive deltas
                for (int delta_size : delta_sizes)
                {
                    // Use delta sizes to detect if packet was received.
                    if (delta_size == 0)
                    {
                        packets_.add(new UnreceivedPacketReport(seq_no.getValue()));
                    }
                    else
                    {
                        packets_.add(new ReceivedPacketReport(seq_no.getValue(), (short) 0));
                    }
                    seq_no = seq_no.plus(1);
                }
            }
            data = new TccMemberData(
                base_seq_no_.getValue(),
                base_time_ticks_,
                encoded_chunks_,
                last_chunk_,
                num_seq_no_,
                last_timestamp_,
                packets_
            );
        }
        return data;
    }

    public int GetPacketStatusCount()
    {
        return getData().num_seq_no_;
    }

    public Instant BaseTime()
    {
        return Instant.EPOCH.plus(DurationKt.times(getData().base_time_ticks_, kBaseScaleFactor));
    }

    public Duration GetBaseDelta(Instant prev_timestamp)
    {
        Duration delta = Duration.between(prev_timestamp, BaseTime());
        // Compensate for wrap around
        if (DurationKt.abs(delta.minus(kTimeWrapPeriod)).compareTo(DurationKt.abs(delta)) < 0)
        {
            delta = delta.minus(kTimeWrapPeriod);
        }
        else if (DurationKt.abs(delta.plus(kTimeWrapPeriod)).compareTo(DurationKt.abs(delta)) < 0)
        {
            delta = delta.plus(kTimeWrapPeriod);
        }
        return delta;
    }

    @Override
    public Iterator<PacketReport> iterator()
    {
        return getData().packets_.iterator();
    }

    @Override
    public RtcpFbTccPacket clone()
    {
        return new RtcpFbTccPacket(cloneBuffer(0), 0, length);
    }
}
