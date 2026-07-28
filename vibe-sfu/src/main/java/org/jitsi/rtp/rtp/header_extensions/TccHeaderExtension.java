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
package org.jitsi.rtp.rtp.header_extensions;

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.util.FieldParsers;

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-2.2
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | L=1   |transport-wide sequence number | zero padding  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
public final class TccHeaderExtension
{
    private TccHeaderExtension()
    {
    }

    public static final int DATA_SIZE_BYTES = 2;

    public static int getSequenceNumber(RtpPacket.HeaderExtension ext)
    {
        return getSequenceNumber(ext.getBuffer(), ext.getDataOffset());
    }

    public static void setSequenceNumber(RtpPacket.HeaderExtension ext, int tccSeqNum)
    {
        setSequenceNumber(ext.getBuffer(), ext.getDataOffset(), tccSeqNum);
    }

    private static int getSequenceNumber(byte[] buf, int offset)
    {
        return FieldParsers.getShortAsInt(buf, offset);
    }

    private static void setSequenceNumber(byte[] buf, int offset, int seqNum)
    {
        ByteArrayExtensions.putShort(buf, offset, (short) seqNum);
    }
}
