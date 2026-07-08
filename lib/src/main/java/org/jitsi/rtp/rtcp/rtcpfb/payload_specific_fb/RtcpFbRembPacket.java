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

package org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb;

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.util.FieldParsers;

import java.util.ArrayList;
import java.util.List;

/**
 * https://tools.ietf.org/html/draft-alvestrand-rmcat-remb-03
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P| FMT=15  |   PT=206      |             length            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Unique identifier 'R' 'E' 'M' 'B'                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  Num SSRC     | BR Exp    |  BR Mantissa                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |   SSRC feedback                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ...                                                          |
 *
 * @author George Politis
 * @author Boris Grozev
 */
public class RtcpFbRembPacket extends PayloadSpecificRtcpFbPacket
{
    public static final int FMT = 15;

    public static final int REMB_OFF = FCI_OFFSET;
    public static final int REMB_LEN = 4;
    public static final int NUM_SSRC_OFF = REMB_OFF + REMB_LEN;
    public static final int NUM_SSRC_LEN = 1;
    public static final int BR_OFF = NUM_SSRC_OFF + NUM_SSRC_LEN;
    public static final int BR_LEN = 3;
    public static final int SSRCS_OFF = BR_OFF + BR_LEN;

    private final long bitrate;
    private final int numSsrc;
    private List<Long> ssrcs;

    public RtcpFbRembPacket(byte[] buffer, int offset, int length)
    {
        super(buffer, offset, length);
        this.bitrate = getBitrate(buffer, offset);
        this.numSsrc = getNumSsrc(buffer, offset);
    }

    public long getBitrate()
    {
        return bitrate;
    }

    public int getNumSsrc()
    {
        return numSsrc;
    }

    /**
     * one or more SSRC entries which this feedback message applies to.
     */
    public List<Long> getSsrcs()
    {
        if (ssrcs == null)
        {
            List<Long> result = new ArrayList<>(numSsrc);
            for (int i = 0; i < numSsrc; i++)
            {
                result.add(getSsrc(buffer, offset, i));
            }
            ssrcs = result;
        }
        return ssrcs;
    }

    @Override
    public RtcpFbRembPacket clone()
    {
        return new RtcpFbRembPacket(cloneBuffer(0), 0, length);
    }

    public static int getBrExp(byte[] buf, int baseOffset)
    {
        return FieldParsers.getBitsAsInt(buf, baseOffset + BR_OFF, 0, 6);
    }

    public static int getBrMantissa(byte[] buf, int baseOffset)
    {
        return (FieldParsers.getBitsAsInt(buf, baseOffset + BR_OFF, 6, 2) << 16)
            + FieldParsers.getShortAsInt(buf, baseOffset + BR_OFF + 1);
    }

    public static long getBitrate(byte[] buf, int baseOffset)
    {
        int mantissa = getBrMantissa(buf, baseOffset);
        int exp = getBrExp(buf, baseOffset);
        long brBps = ((long) mantissa) << exp;
        if ((int) (brBps >> exp) != mantissa)
        {
            // This block catches a Java long overflow (i.e. the bitrate larger than Long.MAX_VALUE). Although this
            // could potentially indicate a malformed remb or a bug in our code, we chose to interpret as the remote
            // party trying to signal unbounded bandwidth.
            return Long.MAX_VALUE;
        }

        return brBps;
    }

    public static int getNumSsrc(byte[] buf, int baseOffset)
    {
        return FieldParsers.getByteAsInt(buf, baseOffset + NUM_SSRC_OFF);
    }

    public static long getSsrc(byte[] buf, int baseOffset, int ssrcIndex)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + SSRCS_OFF + ssrcIndex * 4);
    }

    /**
     * Translation of the Kotlin {@code Pair<Int, Int>} returned by {@code getExpAndMantissa}.
     */
    public static final class ExpAndMantissa
    {
        private final int exp;
        private final int mantissa;

        public ExpAndMantissa(int exp, int mantissa)
        {
            this.exp = exp;
            this.mantissa = mantissa;
        }

        public int getExp()
        {
            return exp;
        }

        public int getMantissa()
        {
            return mantissa;
        }
    }

    public static ExpAndMantissa getExpAndMantissa(long brBps)
    {
        // 6 bit Exp
        // 18 bit mantissa
        int exp = 0;
        long mantissa = brBps > 0 ? brBps : 0;

        // 0x3ffff (262143) is the max value that can be put into an 18-bit unsigned integer
        while (mantissa > 0x3ffff)
        {
            mantissa = mantissa >> 1;
            ++exp;
        }

        return new ExpAndMantissa(exp, (int) mantissa);
    }

    public static void setRemb(byte[] buf, int baseOffset)
    {
        buf[baseOffset + REMB_OFF] = (byte) 'R';
        buf[baseOffset + REMB_OFF + 1] = (byte) 'E';
        buf[baseOffset + REMB_OFF + 2] = (byte) 'M';
        buf[baseOffset + REMB_OFF + 3] = (byte) 'B';
    }

    public static void setNumSsrc(byte[] buf, int off, int value)
    {
        buf[off + NUM_SSRC_OFF] = (byte) value;
    }

    public static void setBrExp(byte[] buf, int baseOffset, int value)
    {
        buf[baseOffset + BR_OFF] = (byte) ((buf[baseOffset + BR_OFF] & 0x03) | ((value & 0x3f) << 2));
    }

    public static void setBrMantissa(byte[] buf, int baseOffset, int value)
    {
        buf[baseOffset + BR_OFF] = (byte) ((buf[baseOffset + BR_OFF] & 0xfc) | ((value >> 16) & 0x03));
        buf[baseOffset + BR_OFF + 1] = (byte) ((value >> 8) & 0xff);
        buf[baseOffset + BR_OFF + 2] = (byte) (value & 0xff);
    }

    public static void setSsrcs(byte[] buf, int baseOffset, List<Long> ssrcs)
    {
        int ssrcsOff = baseOffset + SSRCS_OFF;
        for (Long ssrc : ssrcs)
        {
            ByteArrayExtensions.putInt(buf, ssrcsOff, ssrc.intValue());
            ssrcsOff += 4;
        }
    }
}
