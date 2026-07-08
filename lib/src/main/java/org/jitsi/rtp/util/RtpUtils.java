/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.rtp.util;

import org.jitsi.utils.TimeUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class RtpUtils
{
    private RtpUtils()
    {
    }

    /**
     * {@code sizeBytes} MUST including padding (i.e. it should be 32-bit word aligned)
     */
    public static int calculateRtcpLengthFieldValue(int sizeBytes)
    {
        if (sizeBytes % 4 != 0)
        {
            throw new RuntimeException("Invalid RTCP size value");
        }
        return (sizeBytes / 4) - 1;
    }

    /**
     * Get the number of bytes needed to pad {@code dataSizeBytes} bytes to a 4-byte word boundary.
     */
    public static int getNumPaddingBytes(int dataSizeBytes)
    {
        switch (dataSizeBytes % 4)
        {
            case 0:
                return 0;
            case 1:
                return 3;
            case 2:
                return 2;
            case 3:
                return 1;
            default:
                return 0; // The above is exhaustive.
        }
    }

    /**
     * Returns the delta between two RTP sequence numbers, taking into account
     * rollover.  This will return the 'shortest' delta between the two
     * sequence numbers in the form of the number you'd add to b to get a. e.g.:
     * getSequenceNumberDelta(1, 10) -&gt; -9 (10 + -9 = 1)
     * getSequenceNumberDelta(1, 65530) -&gt; 7 (65530 + 7 = 1)
     * @return the delta between two RTP sequence numbers (modulo 2^16).
     */
    public static int getSequenceNumberDelta(int a, int b)
    {
        return getSequenceNumberDeltaAsShort(a, b);
    }

    /**
     * Like {@link #getSequenceNumberDelta(int, int)}, but returning the delta as a {@code short}.
     *
     * TODO: since short can fully represent sequence number deltas,
     *   change this to be the public API?
     */
    private static short getSequenceNumberDeltaAsShort(int a, int b)
    {
        /* Coercing to short forces diff to the range -0x8000 - 0x7fff,
           which is what we want. */
        return (short) (a - b);
    }

    /**
     * Apply a delta to a given sequence number and return the result (taking
     * rollover into account)
     * @param start the starting sequence number
     * @param delta the delta to be applied
     * @return the sequence number resulting from doing "start + delta"
     */
    public static int applySequenceNumberDelta(int start, int delta)
    {
        return (start + delta) & 0xffff;
    }

    /**
     * Apply a delta to a given RTP timestamp and return the result (taking
     * rollover into account)
     * @param start the starting timestamp
     * @param delta the delta to be applied
     * @return the timestamp result from doing "start + delta"
     */
    public static long applyTimestampDelta(long start, long delta)
    {
        return (start + delta) & 0xffff_ffffL;
    }

    public static boolean isNewerSequenceNumberThan(int a, int b)
    {
        return getSequenceNumberDeltaAsShort(a, b) > 0;
    }

    public static boolean isOlderSequenceNumberThan(int a, int b)
    {
        return getSequenceNumberDeltaAsShort(a, b) < 0;
    }

    public static boolean isNewerTimestampThan(long a, long b)
    {
        return getTimestampDiffAsInt(a, b) > 0;
    }

    public static boolean isOlderTimestampThan(long a, long b)
    {
        return getTimestampDiffAsInt(a, b) < 0;
    }

    /**
     * Returns the difference between two RTP timestamps.
     * @return the difference between two RTP timestamps.
     */
    public static long getTimestampDiff(long a, long b)
    {
        return getTimestampDiffAsInt(a, b);
    }

    /**
     * Returns the difference between two RTP timestamps as an {@code int}.
     */
    public static int getTimestampDiffAsInt(long a, long b)
    {
        /* Coercing to int forces diff to the range -0x8000_0000 - 0x7fff_ffff,
          which is what we want. */
        return (int) (a - b);
    }

    /**
     * Returns a sequence of ints from olderSeqNum (exclusive) to newerSeqNum (exclusive),
     * taking rollover into account
     */
    public static Iterable<Integer> sequenceNumbersBetween(int olderSeqNum, int newerSeqNum)
    {
        List<Integer> result = new ArrayList<>();
        int currSeqNum = olderSeqNum;
        while (true)
        {
            currSeqNum = (currSeqNum + 1) % 0x1_0000;
            if (currSeqNum == newerSeqNum)
            {
                break;
            }
            result.add(currSeqNum);
        }
        return result;
    }

    /**
     * Given {@code timestampMs} (a timestamp in milliseconds), convert it to an NTP timestamp represented
     * as a pair of ints: the first one being the most significant word and the second being the least
     * significant word.
     */
    public static long millisToNtpTimestamp(long timestampMs)
    {
        return TimeUtils.toNtpTime(timestampMs);
    }

    public static long convertRtpTimestampToMs(int rtpTimestamp, int ticksPerSecond)
    {
        return (long) ((rtpTimestamp / ((double) ticksPerSecond)) * 1000);
    }

    public static Instant convertRtpTimestampToInstant(int rtpTimestamp, int ticksPerSecond)
    {
        return Instant.EPOCH.plus(Duration.ofSeconds(rtpTimestamp).dividedBy(ticksPerSecond));
    }

    public static boolean isPadding(int i)
    {
        return isPadding((byte) i);
    }

    public static boolean isPadding(byte b)
    {
        return b == 0x00;
    }

    /**
     * Returns true if the RTP sequence number represented by {@code seqNum} represents a more recent RTP packet
     * than the one represented by {@code otherSeqNum}
     */
    public static boolean isNewerThan(int seqNum, int otherSeqNum)
    {
        return isNewerSequenceNumberThan(seqNum, otherSeqNum);
    }

    public static boolean isOlderThan(int seqNum, int otherSeqNum)
    {
        return isOlderSequenceNumberThan(seqNum, otherSeqNum);
    }

    /**
     * Returns true if getting to {@code otherSeqNum} from the current sequence number involves wrapping around
     */
    public static boolean rolledOverTo(int seqNum, int otherSeqNum)
    {
        // If, according to isOlderThan, seqNum is older than otherSeqNum and
        // yet otherSeqNum is less than seqNum, then we wrapped around to get from seqNum to
        // otherSeqNum
        return isOlderThan(seqNum, otherSeqNum) && otherSeqNum < seqNum;
    }

    /**
     * Returns true if {@code seqNum} is sequentially after {@code otherSeqNum}, according to the rules of RTP
     * sequence numbers
     */
    public static boolean isNextAfter(int seqNum, int otherSeqNum)
    {
        return getSequenceNumberDelta(seqNum, otherSeqNum) == 1;
    }

    /**
     * Return the amount of packets between the RTP sequence number represented by {@code seqNum} and the
     * {@code otherSeqNum}.  NOTE: {@code seqNum} must represent an older RTP sequence number than
     * {@code otherSeqNum} (TODO: validate/enforce that)
     */
    public static int numPacketsTo(int seqNum, int otherSeqNum)
    {
        return -getSequenceNumberDelta(seqNum, otherSeqNum) - 1;
    }
}
