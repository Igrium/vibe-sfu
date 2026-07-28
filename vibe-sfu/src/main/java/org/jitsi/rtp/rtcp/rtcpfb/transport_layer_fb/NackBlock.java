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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb;

import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.util.FieldParsers;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;

/**
 * Translation of the private {@code NackBlock} class from {@code RtcpFbNackPacket.kt}.
 *
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class NackBlock
{
    static final int SIZE_BYTES = 4;

    private final SortedSet<Integer> missingSeqNums;

    NackBlock(SortedSet<Integer> missingSeqNums)
    {
        this.missingSeqNums = missingSeqNums;
    }

    void writeTo(byte[] buf, int offset)
    {
        putMissingSeqNums(buf, offset, missingSeqNums);
    }

    static List<Integer> getMissingSeqNums(byte[] buf, int offset)
    {
        int packetId = FieldParsers.getShortAsInt(buf, offset);
        int blp = FieldParsers.getShortAsInt(buf, offset + 2);
        List<Integer> missingSeqNums = new ArrayList<>();
        missingSeqNums.add(packetId);
        for (int shiftAmount = 0; shiftAmount <= 15; shiftAmount++)
        {
            if (((blp >>> shiftAmount) & 0x1) == 1)
            {
                missingSeqNums.add((packetId + shiftAmount + 1) & 0xffff);
            }
        }
        return missingSeqNums;
    }

    /**
     * {@code missingSeqNums.last() - missingSeqNums.first()} MUST be &lt;= 16
     * {@code offset} should point to the start of where this NackBlock will go
     */
    static void putMissingSeqNums(byte[] buf, int offset, SortedSet<Integer> missingSeqNums)
    {
        int packetId = missingSeqNums.first();
        ByteArrayExtensions.putShort(buf, offset, (short) packetId);
        int blpField = 0;
        for (int bitPos = 16; bitPos >= 1; bitPos--)
        {
            if (missingSeqNums.contains(bitPos + packetId))
            {
                blpField = blpField | 1;
            }
            // Don't shift the last time
            if (bitPos != 1)
            {
                blpField = blpField << 1;
            }
        }
        ByteArrayExtensions.putShort(buf, offset + 2, (short) blpField);
    }
}
