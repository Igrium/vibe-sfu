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

import org.jitsi.rtp.util.FieldParsers;
import org.jitsi.rtp.util.RtpUtils;

import java.util.ArrayList;
import java.util.List;

// Offset should point to the start of this sdes chunk
public class SdesChunk
{
    public static final int SSRC_OFFSET = 0;
    public static final int SDES_ITEMS_OFFSET = 4;

    private final byte[] buffer;
    private final int offset;

    private Integer sizeBytes;
    private Long ssrc;
    private List<SdesItem> sdesItems;

    public SdesChunk(byte[] buffer, int offset)
    {
        this.buffer = buffer;
        this.offset = offset;
    }

    public int getSizeBytes()
    {
        if (sizeBytes == null)
        {
            int itemsSize = 0;
            for (SdesItem item : getSdesItems())
            {
                itemsSize += item.getSizeBytes();
            }
            int dataSize = 4 + itemsSize;
            sizeBytes = dataSize + RtpUtils.getNumPaddingBytes(dataSize);
        }
        return sizeBytes;
    }

    public long getSsrc()
    {
        if (ssrc == null)
        {
            ssrc = getSsrc(buffer, offset);
        }
        return ssrc;
    }

    public List<SdesItem> getSdesItems()
    {
        if (sdesItems == null)
        {
            sdesItems = getSdesItems(buffer, offset);
        }
        return sdesItems;
    }

    public static long getSsrc(byte[] buf, int baseOffset)
    {
        return FieldParsers.getIntAsLong(buf, baseOffset + SSRC_OFFSET);
    }

    public static List<SdesItem> getSdesItems(byte[] buf, int baseOffset)
    {
        int currOffset = baseOffset + SDES_ITEMS_OFFSET;
        List<SdesItem> sdesItems = new ArrayList<>();
        while (true)
        {
            SdesItem currItem = SdesItem.parse(buf, currOffset);
            if (currItem == EmptySdesItem.INSTANCE)
            {
                break;
            }
            sdesItems.add(currItem);
            currOffset += currItem.getSizeBytes();
        }
        return sdesItems;
    }
}
