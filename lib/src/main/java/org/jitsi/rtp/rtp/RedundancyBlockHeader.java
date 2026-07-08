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

public class RedundancyBlockHeader extends BlockHeader
{
    /**
     * The maximum value of the {@link #timestampOffset} field (a 14-bit uint).
     */
    public static final int MAX_TIMESTAMP_OFFSET = 0x3fff;

    /**
     * The (negative) offset of the timestamp relative to the RTP packet/primary block.
     */
    private final int timestampOffset;

    /**
     * The length of the block's payload in bytes.
     */
    private final int length;

    public RedundancyBlockHeader(byte pt, int timestampOffset, int length)
    {
        super(pt);
        this.timestampOffset = timestampOffset;
        this.length = length;

        if (!(timestampOffset <= MAX_TIMESTAMP_OFFSET))
        {
            throw new IllegalArgumentException("Invalid timestampOffset: " + timestampOffset);
        }
    }

    public int getTimestampOffset()
    {
        return timestampOffset;
    }

    public int getLength()
    {
        return length;
    }

    @Override
    public int getHeaderLength()
    {
        return 4;
    }

    @Override
    public int write(byte[] buffer, int offset)
    {
        buffer[offset] = (byte) (getPt() | 0x80);
        buffer[offset + 1] = (byte) (timestampOffset >> 6);
        buffer[offset + 2] = (byte) (((byte) ((timestampOffset & 0x3f) << 2)) | ((byte) (length >> 8) & (byte) 0x03));
        buffer[offset + 3] = (byte) (length & 0xff);
        return 4;
    }
}
