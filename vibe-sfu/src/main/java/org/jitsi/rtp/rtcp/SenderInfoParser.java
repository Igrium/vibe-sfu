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
import org.jitsi.rtp.util.FieldParsers;

/**
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * sender |              NTP timestamp, most significant word             |
 * info   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |             NTP timestamp, least significant word             |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         RTP timestamp                         |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                     sender's packet count                     |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                      sender's octet count                     |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *
 * RTCP SenderInfo block
 */
public class SenderInfoParser
{
    public static final int SIZE_BYTES = 20;
    public static final int NTP_TS_MSW_OFFSET = 0;
    public static final int NTP_TS_LSW_OFFSET = 4;
    public static final int RTP_TS_OFFSET = 8;
    public static final int SENDERS_PACKET_COUNT_OFFSET = 12;
    public static final int SENDERS_OCTET_COUNT_OFFSET = 16;

    public static long getNtpTimestampMsw(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + NTP_TS_MSW_OFFSET);
    }

    public static void setNtpTimestampMsw(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + NTP_TS_MSW_OFFSET, (int) value);
    }

    public static long getNtpTimestampLsw(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + NTP_TS_LSW_OFFSET);
    }

    public static void setNtpTimestampLsw(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + NTP_TS_LSW_OFFSET, (int) value);
    }

    public static long getRtpTimestamp(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + RTP_TS_OFFSET);
    }

    public static void setRtpTimestamp(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + RTP_TS_OFFSET, (int) value);
    }

    public static long getSendersPacketCount(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + SENDERS_PACKET_COUNT_OFFSET);
    }

    public static void setSendersPacketCount(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + SENDERS_PACKET_COUNT_OFFSET, (int) value);
    }

    public static long getSendersOctetCount(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + SENDERS_OCTET_COUNT_OFFSET);
    }

    public static void setSendersOctetCount(byte[] buf, int baseOffset, long value)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + SENDERS_OCTET_COUNT_OFFSET, (int) value);
    }
}
