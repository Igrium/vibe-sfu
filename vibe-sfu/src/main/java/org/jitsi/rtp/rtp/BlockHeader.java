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
import org.jitsi.rtp.util.FieldParsers;

/**
 * Translation of the {@code BlockHeader} sealed class from {@code RedPacketParser.kt}.
 */
public abstract class BlockHeader
{
    /**
     * The block's payload type.
     */
    private final byte pt;

    protected BlockHeader(byte pt)
    {
        this.pt = pt;
    }

    public byte getPt()
    {
        return pt;
    }

    public abstract int getHeaderLength();

    public abstract int write(byte[] buffer, int offset);

    /**
     * See RFC2198.
     *
     * Header blocks before the last (for redundant blocks):
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |1|   block PT  |  timestamp offset         |   block length    |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     *
     * The last header block (for the primary block):
     *  0
     *  0 1 2 3 4 5 6 7
     * +-+-+-+-+-+-+-+-+
     * |0|   block PT  |
     * +-+-+-+-+-+-+-+-+
     *
     */
    public static BlockHeader parse(byte[] buffer, int offset)
    {
        boolean follow = ByteArrayExtensions.getBitAsBool(buffer, offset, 0);
        byte pt = (byte) FieldParsers.getBitsAsInt(buffer, offset, 1, 7);

        if (!follow)
        {
            return new PrimaryBlockHeader(pt);
        }

        int timestampOffset =
            (Unsigned.toPositiveInt(buffer[offset + 1]) << 6) + FieldParsers.getBitsAsInt(buffer, offset + 2, 0, 6);
        int blockLength =
            (FieldParsers.getBitsAsInt(buffer, offset + 2, 6, 2) << 8) + Unsigned.toPositiveInt(buffer[offset + 3]);

        return new RedundancyBlockHeader(pt, timestampOffset, blockLength);
    }
}
