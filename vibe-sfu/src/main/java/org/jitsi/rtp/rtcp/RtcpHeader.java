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
 * Models the RTCP header as defined in https://tools.ietf.org/html/rfc3550#section-6.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|    RC   |   PT=SR=200   |             length            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         SSRC of sender                        |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *
 * length: 16 bits
 *   The length of this RTCP packet in 32-bit words minus one,
 *   including the header and any padding.  (The offset of one makes
 *   zero a valid length and avoids a possible infinite loop in
 *   scanning a compound RTCP packet, while counting 32-bit words
 *   avoids a validity check for a multiple of 4.)
 *
 * @author Brian Baldino
 *
 * TODO: Use the same utility function in RtpHeader and RtcpHeader
 */
public class RtcpHeader
{
    public static final int SIZE_BYTES = 8;
    public static final int VERSION_OFFSET = 0;
    public static final int PADDING_OFFSET = 0;
    public static final int REPORT_COUNT_OFFSET = 0;
    public static final int PACKET_TYPE_OFFSET = 1;
    public static final int LENGTH_OFFSET = 2;
    public static final int SENDER_SSRC_OFFSET = 4;

    public static int getVersion(byte[] buf, int headerStartOffset)
    {
        return FieldParsers.getBitsAsInt(buf, headerStartOffset + VERSION_OFFSET, 0, 2);
    }

    public static void setVersion(byte[] buf, int headerStartOffset, int version)
    {
        FieldParsers.putNumberAsBits(buf, headerStartOffset + VERSION_OFFSET, 0, 2, version);
    }

    public static boolean hasPadding(byte[] buf, int headerStartOffset)
    {
        return ByteArrayExtensions.getBitAsBool(buf, headerStartOffset + PADDING_OFFSET, 2);
    }

    public static void setPadding(byte[] buf, int headerStartOffset, boolean hasPadding)
    {
        ByteArrayExtensions.putBitAsBoolean(buf, headerStartOffset + PADDING_OFFSET, 2, hasPadding);
    }

    public static int getReportCount(byte[] buf, int headerStartOffset)
    {
        return FieldParsers.getBitsAsInt(buf, headerStartOffset + REPORT_COUNT_OFFSET, 3, 5);
    }

    public static void setReportCount(byte[] buf, int headerStartOffset, int reportCount)
    {
        FieldParsers.putNumberAsBits(buf, headerStartOffset + REPORT_COUNT_OFFSET, 3, 5, reportCount);
    }

    public static int getPacketType(byte[] buf, int headerStartOffset)
    {
        return FieldParsers.getByteAsInt(buf, headerStartOffset + PACKET_TYPE_OFFSET);
    }

    public static void setPacketType(byte[] buf, int headerStartOffset, int packetType)
    {
        buf[headerStartOffset + PACKET_TYPE_OFFSET] = (byte) packetType;
    }

    // TODO document (bytes or words?)
    public static int getLength(byte[] buf, int headerStartOffset)
    {
        return FieldParsers.getShortAsInt(buf, headerStartOffset + LENGTH_OFFSET);
    }

    public static void setLength(byte[] buf, int headerStartOffset, int length)
    {
        ByteArrayExtensions.putShort(buf, headerStartOffset + LENGTH_OFFSET, (short) length);
    }

    public static long getSenderSsrc(byte[] buf, int headerStartOffset)
    {
        return FieldParsers.getIntAsLong(buf, headerStartOffset + SENDER_SSRC_OFFSET);
    }

    public static void setSenderSsrc(byte[] buf, int headerStartOffset, long senderSsrc)
    {
        ByteArrayExtensions.putInt(buf, headerStartOffset + SENDER_SSRC_OFFSET, (int) senderSsrc);
    }
}
