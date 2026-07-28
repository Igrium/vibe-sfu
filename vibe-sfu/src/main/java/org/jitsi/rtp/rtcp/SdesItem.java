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
import org.jitsi.rtp.util.BufferPool;
import org.jitsi.rtp.util.FieldParsers;

/**
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |    Type       |     length    |          data               ...
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * length = the length of the data field
 */
public abstract class SdesItem
{
    public static final int SDES_ITEM_HEADER_SIZE = 2;
    public static final int TYPE_OFFSET = 0;
    public static final int LENGTH_OFFSET = 1;
    public static final int DATA_OFFSET = 2;

    private final SdesItemType type;

    protected SdesItem(SdesItemType type)
    {
        this.type = type;
    }

    public SdesItemType getType()
    {
        return type;
    }

    public abstract int getSizeBytes();

    public static int getType(byte[] buf, int baseOffset)
    {
        return FieldParsers.getByteAsInt(buf, baseOffset + TYPE_OFFSET);
    }

    public static int getLength(byte[] buf, int baseOffset)
    {
        return FieldParsers.getByteAsInt(buf, baseOffset + LENGTH_OFFSET);
    }

    public static byte[] copyData(byte[] buf, int baseOffset, int dataLength)
    {
        if (dataLength <= 0)
        {
            return ByteArrayExtensions.ByteArrayUtilsHolder.emptyByteArray;
        }
        byte[] copy = BufferPool.getArray(dataLength);
        System.arraycopy(buf, baseOffset + DATA_OFFSET, copy, 0, dataLength);
        return copy;
    }

    /**
     * {@code buf}'s current position should be at the beginning of
     * the SDES item
     */
    public static SdesItem parse(byte[] buf, int offset)
    {
        int typeValue = getType(buf, offset);
        SdesItemType type = SdesItemType.fromInt(typeValue);
        if (type == SdesItemType.EMPTY)
        {
            return EmptySdesItem.INSTANCE;
        }
        else
        {
            int length = getLength(buf, offset);
            if (type == SdesItemType.CNAME)
            {
                return new CnameSdesItem(buf, offset, length);
            }
            else
            {
                return new UnknownSdesItem(typeValue, buf, offset, length);
            }
        }
    }
}
