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

package org.jitsi.rtp.util;

import org.jitsi.rtp.extensions.ByteExtensions;
import org.jitsi.rtp.extensions.bytearray.ByteArrayExtensions;
import org.jitsi.rtp.extensions.unsigned.Unsigned;

// TODO: could do some kind of 'Offset' inline class? or 'field' which described the offset
// and size?
public final class FieldParsers
{
    private FieldParsers()
    {
    }

    public static int getBitsAsInt(byte[] buf, int byteOffset, int bitStartPos, int numBits)
    {
        return Unsigned.toPositiveInt(ByteExtensions.getBits(buf[byteOffset], bitStartPos, numBits));
    }

    public static void putNumberAsBits(byte[] buf, int byteOffset, int bitOffset, int numBits, Number value)
    {
        ByteArrayExtensions.putBits(buf, byteOffset, bitOffset, value.byteValue(), numBits);
    }

    public static int getByteAsInt(byte[] buf, int offset)
    {
        return Unsigned.toPositiveInt(buf[offset]);
    }

    public static int getShortAsInt(byte[] buf, int offset)
    {
        return Unsigned.toPositiveInt(ByteArrayExtensions.getShort(buf, offset));
    }

    public static int get3BytesAsInt(byte[] buf, int offset)
    {
        return Unsigned.toPositiveInt(ByteArrayExtensions.get3Bytes(buf, offset));
    }

    public static long getIntAsLong(byte[] buf, int offset)
    {
        return Unsigned.toPositiveLong(ByteArrayExtensions.getInt(buf, offset));
    }
}
