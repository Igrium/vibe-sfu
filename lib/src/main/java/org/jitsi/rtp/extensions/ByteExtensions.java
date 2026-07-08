/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.rtp.extensions;

/**
 * Translation of {@code extensions/Byte.kt}'s extension functions on {@link Byte}/{@code byte}.
 */
public final class ByteExtensions
{
    private ByteExtensions()
    {
    }

    /**
     * Return the value of the bit at position {@code bitPos}, where
     * 0 represents the left-most bit and 7 represents the right-most
     * bit of the current byte
     */
    public static int getBit(byte b, int bitPos)
    {
        int mask = 0b1 << (7 - bitPos);
        return (b & mask) >>> (7 - bitPos);
    }

    /**
     * Return a byte setting or unsetting the bit at {@code bitPos} of a byte
     * according to {@code isSet}
     */
    public static byte putBit(byte b, int bitPos, boolean isSet)
    {
        if (isSet)
        {
            return (byte) (b | (0b10000000 >>> bitPos));
        }
        else
        {
            return (byte) (b & ~(0b10000000 >>> bitPos));
        }
    }

    /**
     * Return a byte setting or unsetting the bit or bits specified by {@code mask}
     * of a byte according to {@code isSet}
     */
    public static byte putBitWithMask(byte b, byte mask, boolean isSet)
    {
        if (isSet)
        {
            return (byte) (b | mask);
        }
        else
        {
            return (byte) (b & ~mask);
        }
    }

    /**
     * Return a byte with the right-most {@code numBits} bits from {@code src} put into {@code dest}
     * starting at {@code bitStartPos}
     * {@code bitStartPos} is a 0 based index into the byte {@code dest}, where the MSB
     * is position 0 and the LSB is position 7.
     * Given the values:
     * bitStartPos = 4
     * numBits = 3
     * src = 0b101
     * dest = 0b00000000
     *
     * The returned Byte will be:
     * 0b00001010
     */
    public static byte putBits(byte dest, int bitStartPos, int numBits, byte src)
    {
        // Start the position in src to the first bit we'll assign.
        int valueBitPosition = 7 - numBits + 1;
        byte result = dest;
        for (int i = 0; i < numBits; i++)
        {
            boolean isSet = getBitAsBool(src, valueBitPosition + i);
            result = putBit(result, bitStartPos + i, isSet);
        }
        return result;
    }

    /**
     * Get the bit at {@code bitPos} as a {@code boolean}.  Will return {@code true}
     * if the bit is set (1) and {@code false} if it is unset (0)
     */
    public static boolean getBitAsBool(byte b, int bitPos)
    {
        return getBit(b, bitPos) == 1;
    }

    /**
     * Get {@code numBits} bits starting at {@code bitStartPos}, shift them
     * all the way to the right and return the result as a {@code byte}
     * {@code numBits} must be &lt;= 8
     */
    public static byte getBits(byte b, int bitStartPos, int numBits)
    {
        // Subtract 1 since the bit at 'bitStartPos' will be included, e.g.:
        // A call getBits(4, 2) will read bits 4 and 5
        int bitEndPos = bitStartPos + numBits - 1;
        int result = 0;
        // shiftOffset represents how far, from the right-most
        // bit position, the bit at bitStartPos should be
        // at in the result
        int shiftOffset = bitEndPos - bitStartPos;
        int index = 0;
        for (int value = bitStartPos; value <= bitEndPos; value++, index++)
        {
            result = result | (getBit(b, value) << (shiftOffset - index));
        }
        return (byte) result;
    }
}
