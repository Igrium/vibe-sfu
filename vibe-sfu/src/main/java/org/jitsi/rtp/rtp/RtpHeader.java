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

package org.jitsi.rtp.rtp;

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.extensions.unsigned.Unsigned;
import org.jitsi.rtp.rtp.header_extensions.HeaderExtensionHelpers;
import org.jitsi.rtp.util.FieldParsers;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link RtpHeader} exists only as a set of helper methods to retrieve and set fields inside of
 * a {@code byte[]}.
 *
 * It covers the RTP header fields until where the variability starts (the CSRCs list)
 *
 *
 * https://tools.ietf.org/html/rfc3550#section-5.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           synchronization source (SSRC) identifier            |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |            contributing source (CSRC) identifiers             |
 * |                             ....                              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |              ...extensions (if present)...                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * @author Brian Baldino
 */
public class RtpHeader
{
    public static final int FIXED_HEADER_SIZE_BYTES = 12;
    public static final int CSRCS_OFFSET = 12;

    // The size of the RTP Extension header block
    public static final int EXT_HEADER_SIZE_BYTES = 4;
    public static final int VERSION = 2;

    public static int getVersion(byte[] buf, int baseOffset)
    {
        return (buf[baseOffset] & 0xC0) >>> 6;
    }

    public static void setVersion(byte[] buf, int baseOffset, int version)
    {
        buf[baseOffset] = (byte) ((buf[baseOffset] & ~0xC0) | ((version << 6) & 0xC0));
    }

    public static boolean hasPadding(byte[] buf, int baseOffset)
    {
        return (buf[baseOffset] & 0x20) == 0x20;
    }

    public static void setPadding(byte[] buf, int baseOffset, boolean hasPadding)
    {
        if (hasPadding)
        {
            buf[baseOffset] = (byte) (buf[baseOffset] | 0x20);
        }
        else
        {
            buf[baseOffset] = (byte) (buf[baseOffset] & ~0x20);
        }
    }

    public static boolean hasExtensions(byte[] buf, int baseOffset)
    {
        return (buf[baseOffset] & 0x10) == 0x10;
    }

    public static void setHasExtensions(byte[] buf, int baseOffset, boolean hasExtension)
    {
        if (hasExtension)
        {
            buf[baseOffset] = (byte) (buf[baseOffset] | 0x10);
        }
        else
        {
            buf[baseOffset] = (byte) (buf[baseOffset] & ~0x10);
        }
    }

    public static int getCsrcCount(byte[] buf, int baseOffset)
    {
        return buf[baseOffset] & 0x0F;
    }

    public static void setCsrcCount(byte[] buf, int baseOffset, int csrcCount)
    {
        buf[baseOffset] = (byte) ((buf[baseOffset] & 0xF0) | (csrcCount & 0x0F));
    }

    public static boolean getMarker(byte[] buf, int baseOffset)
    {
        return (buf[baseOffset + 1] & 0x80) == 0x80;
    }

    public static void setMarker(byte[] buf, int baseOffset, boolean isSet)
    {
        if (isSet)
        {
            buf[baseOffset + 1] = (byte) (buf[baseOffset + 1] | 0x80);
        }
        else
        {
            buf[baseOffset + 1] = (byte) (buf[baseOffset + 1] & ~0x80);
        }
    }

    public static int getPayloadType(byte[] buf, int baseOffset)
    {
        return Unsigned.toPositiveInt((byte) (buf[baseOffset + 1] & 0x7F));
    }

    public static void setPayloadType(byte[] buf, int baseOffset, int payloadType)
    {
        buf[baseOffset + 1] = (byte) ((buf[baseOffset + 1] & 0x80) | (payloadType & 0x7F));
    }

    public static int getSequenceNumber(byte[] buf, int baseOffset)
    {
        return FieldParsers.getShortAsInt(buf, baseOffset + 2);
    }

    public static void setSequenceNumber(byte[] buf, int baseOffset, int sequenceNumber)
    {
        ByteArrayExtensions.putShort(buf, baseOffset + 2, (short) sequenceNumber);
    }

    public static long getTimestamp(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + 4);
    }

    public static void setTimestamp(byte[] buf, int baseOffset, long timestamp)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + 4, (int) timestamp);
    }

    public static long getSsrc(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + 8);
    }

    public static void setSsrc(byte[] buf, int baseOffset, long ssrc)
    {
        ByteArrayExtensions.putInt(buf, baseOffset + 8, (int) ssrc);
    }

    public static List<Long> getCsrcs(byte[] buf, int baseOffset)
    {
        int numCsrcs = getCsrcCount(buf, baseOffset);
        List<Long> csrcs = new ArrayList<>(numCsrcs);
        for (int i = 0; i < numCsrcs; i++)
        {
            csrcs.add(FieldParsers.getIntAsLong(buf, CSRCS_OFFSET + (4 * i)));
        }
        return csrcs;
    }

    /**
     * Assumes there is already proper room for the CSRCS.  Also updates
     * the CSRC count field.
     */
    public static void setCsrcs(byte[] buf, int baseOffset, List<Long> csrcs)
    {
        for (int index = 0; index < csrcs.size(); index++)
        {
            ByteArrayExtensions.putInt(buf, CSRCS_OFFSET + (4 * index), csrcs.get(index).intValue());
        }
        setCsrcCount(buf, baseOffset, csrcs.size());
    }

    private static int getFixedHeaderAndCcLength(byte[] buf, int baseOffset)
    {
        return FIXED_HEADER_SIZE_BYTES + getCsrcCount(buf, baseOffset) * 4;
    }

    /**
     * The length of the entire RTP header, including any extensions, in bytes
     */
    public static int getTotalLength(byte[] buf, int baseOffset)
    {
        int length = getFixedHeaderAndCcLength(buf, baseOffset);

        int extLength;
        if (hasExtensions(buf, baseOffset))
        {
            // Length points to where the ext header would start
            int extHeaderOffset = length;
            extLength = HeaderExtensionHelpers.getExtensionsTotalLength(buf, baseOffset + extHeaderOffset);
        }
        else
        {
            extLength = 0;
        }
        return length + extLength;
    }

    /**
     * The "defined by profile" header extension field.  Only valid if hasExtensions is true, otherwise
     * returns an invalid value (-1)
     */
    public static int getExtensionsProfileType(byte[] buf, int baseOffset)
    {
        if (hasExtensions(buf, baseOffset))
        {
            int extHeaderOffset = getFixedHeaderAndCcLength(buf, baseOffset);
            return HeaderExtensionHelpers.getExtensionsProfileType(buf, baseOffset + extHeaderOffset);
        }
        else
        {
            return -1;
        }
    }
}
