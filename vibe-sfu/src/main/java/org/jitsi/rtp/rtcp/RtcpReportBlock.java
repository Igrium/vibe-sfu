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

package org.jitsi.rtp.rtcp;

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.util.FieldParsers;

import java.util.Objects;

/**
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |                 SSRC_1 (SSRC of first source)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | fraction lost |       cumulative number of packets lost       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           extended highest sequence number received           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      interarrival jitter                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         last SR (LSR)                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   delay since last SR (DLSR)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public class RtcpReportBlock
{
    public static final int SIZE_BYTES = 24;

    // Offsets relative to the start of an RTCP Report Block
    public static final int SSRC_OFFSET = 0;
    public static final int FRACTION_LOST_OFFSET = 4;
    public static final int CUMULATIVE_PACKETS_LOST_OFFSET = 5;
    public static final int EXTENDED_HIGHEST_SEQ_NUM_OFFSET = 8;
    public static final int INTERARRIVAL_JITTER_OFFSET = 12;
    public static final int LAST_SR_TIMESTAMP_OFFSET = 16;
    public static final int DELAY_SINCE_LAST_SR_OFFSET = 20;

    private final long ssrc;
    private final int fractionLost;
    private final int cumulativePacketsLost;
    private final long extendedHighestSeqNum;
    private final long interarrivalJitter;
    private final long lastSrTimestamp;
    private final long delaySinceLastSr;

    private Integer seqNumCycles;
    private Integer seqNum;

    public RtcpReportBlock(
        long ssrc,
        int fractionLost,
        int cumulativePacketsLost,
        long extendedHighestSeqNum,
        long interarrivalJitter,
        long lastSrTimestamp,
        long delaySinceLastSr
    )
    {
        this.ssrc = ssrc;
        this.fractionLost = fractionLost;
        this.cumulativePacketsLost = cumulativePacketsLost;
        this.extendedHighestSeqNum = extendedHighestSeqNum;
        this.interarrivalJitter = interarrivalJitter;
        this.lastSrTimestamp = lastSrTimestamp;
        this.delaySinceLastSr = delaySinceLastSr;
    }

    public RtcpReportBlock(
        long ssrc,
        int fractionLost,
        int cumulativePacketsLost,
        int seqNumCycles,
        int seqNum,
        long interarrivalJitter,
        long lastSrTimestamp,
        long delaySinceLastSr
    )
    {
        this(
            ssrc,
            fractionLost,
            cumulativePacketsLost,
            Unsigned.toPositiveLong((seqNumCycles << 16) + (short) seqNum),
            interarrivalJitter,
            lastSrTimestamp,
            delaySinceLastSr
        );
    }

    public long getSsrc()
    {
        return ssrc;
    }

    public int getFractionLost()
    {
        return fractionLost;
    }

    public int getCumulativePacketsLost()
    {
        return cumulativePacketsLost;
    }

    public long getExtendedHighestSeqNum()
    {
        return extendedHighestSeqNum;
    }

    public long getInterarrivalJitter()
    {
        return interarrivalJitter;
    }

    public long getLastSrTimestamp()
    {
        return lastSrTimestamp;
    }

    public long getDelaySinceLastSr()
    {
        return delaySinceLastSr;
    }

    public int getSeqNumCycles()
    {
        if (seqNumCycles == null)
        {
            seqNumCycles = (int) (extendedHighestSeqNum >>> 16);
        }
        return seqNumCycles;
    }

    public int getSeqNum()
    {
        if (seqNum == null)
        {
            seqNum = Unsigned.toPositiveInt((short) extendedHighestSeqNum);
        }
        return seqNum;
    }

    public void writeTo(byte[] buf, int offset)
    {
        setSsrc(buf, offset, ssrc);
        setFractionLost(buf, offset, fractionLost);
        setCumulativePacketsLost(buf, offset, cumulativePacketsLost);
        setExtendedHighestSeqNum(buf, offset, extendedHighestSeqNum);
        setInterarrivalJitter(buf, offset, interarrivalJitter);
        setLastSrTimestamp(buf, offset, lastSrTimestamp);
        setDelaySinceLastSr(buf, offset, delaySinceLastSr);
    }

    public static RtcpReportBlock fromBuffer(byte[] buffer, int offset)
    {
        long ssrc = getSsrc(buffer, offset);
        int fractionLost = getFractionLost(buffer, offset);
        int cumulativePacketsLost = getCumulativePacketsLost(buffer, offset);
        long extendedHighestSeqNum = getExtendedHighestSeqNum(buffer, offset);
        long interarrivalJitter = getInterarrivalJitter(buffer, offset);
        long lastSrTimestamp = getLastSrTimestamp(buffer, offset);
        long delaySinceLastSr = getDelaySinceLastSr(buffer, offset);

        return new RtcpReportBlock(
            ssrc,
            fractionLost,
            cumulativePacketsLost,
            extendedHighestSeqNum,
            interarrivalJitter,
            lastSrTimestamp,
            delaySinceLastSr
        );
    }

    public static long getSsrc(byte[] buffer, int offset)
    {
        return FieldParsers.getIntAsLong(buffer, offset + SSRC_OFFSET);
    }

    public static void setSsrc(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + SSRC_OFFSET, (int) value);
    }

    public static int getFractionLost(byte[] buffer, int offset)
    {
        return FieldParsers.getByteAsInt(buffer, offset + FRACTION_LOST_OFFSET);
    }

    public static void setFractionLost(byte[] buf, int baseOffset, int value)
    {
        buf[baseOffset + FRACTION_LOST_OFFSET] = (byte) value;
    }

    public static int getCumulativePacketsLost(byte[] buffer, int offset)
    {
        return FieldParsers.get3BytesAsInt(buffer, offset + CUMULATIVE_PACKETS_LOST_OFFSET);
    }

    public static void setCumulativePacketsLost(byte[] buffer, int offset, int value)
    {
        ByteArrayExtensions.put3Bytes(buffer, offset + CUMULATIVE_PACKETS_LOST_OFFSET, value);
    }

    public static long getExtendedHighestSeqNum(byte[] buffer, int offset)
    {
        return FieldParsers.getIntAsLong(buffer, offset + EXTENDED_HIGHEST_SEQ_NUM_OFFSET);
    }

    public static void setExtendedHighestSeqNum(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + EXTENDED_HIGHEST_SEQ_NUM_OFFSET, (int) value);
    }

    public static long getInterarrivalJitter(byte[] buffer, int offset)
    {
        return FieldParsers.getIntAsLong(buffer, offset + INTERARRIVAL_JITTER_OFFSET);
    }

    public static void setInterarrivalJitter(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + INTERARRIVAL_JITTER_OFFSET, (int) value);
    }

    public static long getLastSrTimestamp(byte[] buffer, int offset)
    {
        return FieldParsers.getIntAsLong(buffer, offset + LAST_SR_TIMESTAMP_OFFSET);
    }

    public static void setLastSrTimestamp(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + LAST_SR_TIMESTAMP_OFFSET, (int) value);
    }

    public static long getDelaySinceLastSr(byte[] buffer, int offset)
    {
        return FieldParsers.getIntAsLong(buffer, offset + DELAY_SINCE_LAST_SR_OFFSET);
    }

    public static void setDelaySinceLastSr(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + DELAY_SINCE_LAST_SR_OFFSET, (int) value);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof RtcpReportBlock))
        {
            return false;
        }
        RtcpReportBlock that = (RtcpReportBlock) o;
        return ssrc == that.ssrc && fractionLost == that.fractionLost &&
            cumulativePacketsLost == that.cumulativePacketsLost &&
            extendedHighestSeqNum == that.extendedHighestSeqNum &&
            interarrivalJitter == that.interarrivalJitter &&
            lastSrTimestamp == that.lastSrTimestamp &&
            delaySinceLastSr == that.delaySinceLastSr;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(
            ssrc, fractionLost, cumulativePacketsLost, extendedHighestSeqNum, interarrivalJitter, lastSrTimestamp,
            delaySinceLastSr
        );
    }

    @Override
    public String toString()
    {
        return "RtcpReportBlock(ssrc=" + ssrc + ", fractionLost=" + fractionLost +
            ", cumulativePacketsLost=" + cumulativePacketsLost + ", extendedHighestSeqNum=" + extendedHighestSeqNum +
            ", interarrivalJitter=" + interarrivalJitter + ", lastSrTimestamp=" + lastSrTimestamp +
            ", delaySinceLastSr=" + delaySinceLastSr + ")";
    }
}
