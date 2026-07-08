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

package org.jitsi.rtp.extensions.bytearray;

import org.jitsi.rtp.extensions.ByteExtensions;
import org.jitsi.rtp.util.BufferPool;
import org.jitsi.utils.ByteArrayUtils;

public final class ByteArrayExtensions
{
    private ByteArrayExtensions()
    {
    }

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    /**
     * Put the right-most {@code numBits} bits from {@code src} into the byte at {@code byteIndex}
     * starting at position {@code destBitPos}.  {@code destBitPos} is a 0-based index of the
     * bit in the byte at {@code byteIndex}, where 0 is the MSB and 7 is the LSB.
     */
    public static void putBits(byte[] buf, int byteIndex, int destBitPos, byte src, int numBits)
    {
        byte b = buf[byteIndex];
        b = ByteExtensions.putBits(b, destBitPos, numBits, src);
        buf[byteIndex] = b;
    }

    public static boolean getBitAsBool(byte[] buf, int byteOffset, int bitOffset)
    {
        return ByteExtensions.getBitAsBool(buf[byteOffset], bitOffset);
    }

    public static void putBitAsBoolean(byte[] buf, int byteIndex, int destBitPos, boolean isSet)
    {
        byte b = buf[byteIndex];
        b = ByteExtensions.putBit(b, destBitPos, isSet);
        buf[byteIndex] = b;
    }

    public static short getShort(byte[] buf, int byteIndex)
    {
        return ByteArrayUtils.readShort(buf, byteIndex);
    }

    public static void putShort(byte[] buf, int byteIndex, short value)
    {
        ByteArrayUtils.writeShort(buf, byteIndex, value);
    }

    public static int get3Bytes(byte[] buf, int byteIndex)
    {
        return ByteArrayUtils.readUint24(buf, byteIndex);
    }

    public static void put3Bytes(byte[] buf, int byteIndex, int value)
    {
        ByteArrayUtils.writeUint24(buf, byteIndex, value);
    }

    public static int getInt(byte[] buf, int byteIndex)
    {
        return ByteArrayUtils.readInt(buf, byteIndex);
    }

    public static void putInt(byte[] buf, int byteIndex, int value)
    {
        ByteArrayUtils.writeInt(buf, byteIndex, value);
    }

    public static byte[] byteArrayOf(Number... elements)
    {
        byte[] result = new byte[elements.length];
        for (int i = 0; i < elements.length; i++)
        {
            result[i] = elements[i].byteValue();
        }
        return result;
    }

    /**
     * Shifts the data from {@code startPos} to {@code endPos} {@code numBytes} to the right.
     * Note that {@code startPos} and {@code endPos} are zero-based and numBytes
     * must be positive!
     */
    public static void shiftDataRight(byte[] buf, int startPos, int endPos, int numBytes)
    {
        if (numBytes < 0)
        {
            throw new RuntimeException("");
        }
        for (int index = endPos; index >= startPos; index--)
        {
            buf[index + numBytes] = buf[index];
        }
    }

    public static void shiftDataLeft(byte[] buf, int startPos, int endPos, int numBytes)
    {
        for (int index = startPos; index <= endPos; index++)
        {
            buf[index - numBytes] = buf[index];
        }
    }

    /**
     * Shifts the data from {@code startPos} to {@code endPos} by {@code delta} bytes.
     * if {@code delta} is negative, the data will be shifted to the left,
     * if {@code delta} is positive, the data will be shifted to the right
     */
    public static void shiftData(byte[] buf, int startPos, int endPos, int delta)
    {
        if (delta < 0)
        {
            shiftDataLeft(buf, startPos, endPos, Math.abs(delta));
        }
        else if (delta > 0)
        {
            shiftDataRight(buf, startPos, endPos, delta);
        }
    }

    public static byte[] cloneFromPool(byte[] buf)
    {
        byte[] clone = BufferPool.getArray(buf.length);
        System.arraycopy(buf, 0, clone, 0, buf.length);
        return clone;
    }

    public static byte[] plus(byte[] buf, byte[] other)
    {
        byte[] newArray = BufferPool.getArray(buf.length + other.length);
        System.arraycopy(buf, 0, newArray, 0, buf.length);
        System.arraycopy(other, 0, newArray, buf.length, other.length);

        return newArray;
    }

    /**
     * Print the contents of the {@code byte[]} as hex digits.
     */
    public static String toHex(byte[] buf)
    {
        return toHex(buf, 0, buf.length);
    }

    public static String toHex(byte[] buf, int offset)
    {
        return toHex(buf, offset, buf.length - offset);
    }

    /**
     * Print the contents of the {@code byte[]} as hex digits.
     *
     * @param offset Offset to start at.
     * @param length Maximum number of elements to print.
     */
    public static String toHex(byte[] buf, int offset, int length)
    {
        StringBuilder result = new StringBuilder();

        int end = Math.min(offset + length, buf.length);
        for (int i = offset; i < end; i++)
        {
            int position = i - offset;
            if (position != 0)
            {
                if (position % 16 == 0)
                {
                    result.append("\n");
                }
                else if (position % 4 == 0)
                {
                    result.append(" ");
                }
            }
            int b = buf[i];
            int firstIndex = (b & 0xF0) >> 4;
            int secondIndex = b & 0x0F;
            result.append(HEX_CHARS[firstIndex]);
            result.append(HEX_CHARS[secondIndex]);
        }

        return result.toString();
    }

    /**
     * Returns the hash code of the segment of this {@code byte[]} starting at 'start' and ending in 'end' (exclusive).
     */
    public static int hashCodeOfSegment(byte[] buf, int start, int end)
    {
        int result = 1;
        int from = Math.max(0, Math.min(start, buf.length));
        int to = Math.max(0, Math.min(end, buf.length));
        for (int i = from; i < to; i++)
        {
            result = 31 * result + buf[i];
        }
        return result;
    }

    /** Translation of Kotlin's {@code ByteArrayUtils} companion holder in this file (distinct from
     * {@link org.jitsi.utils.ByteArrayUtils}). */
    public static final class ByteArrayUtilsHolder
    {
        private ByteArrayUtilsHolder()
        {
        }

        public static final byte[] emptyByteArray = BufferPool.getArray(0);
    }
}
