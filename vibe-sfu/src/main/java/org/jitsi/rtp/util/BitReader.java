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

/**
 * Read individual bits, and unaligned sets of bits, from a {@code byte[]}, with an incrementing offset.
 */
/* TODO: put this in jitsi-utils? */
public class BitReader
{
    private final byte[] buf;
    private final int byteOffset;
    private final int byteLength;
    private int offset;
    private final int byteBound;

    public BitReader(byte[] buf)
    {
        this(buf, 0, buf.length);
    }

    public BitReader(byte[] buf, int byteOffset)
    {
        this(buf, byteOffset, buf.length);
    }

    public BitReader(byte[] buf, int byteOffset, int byteLength)
    {
        this.buf = buf;
        this.byteOffset = byteOffset;
        this.byteLength = byteLength;
        this.offset = byteOffset * 8;
        this.byteBound = byteOffset + byteLength;

        if (!(byteOffset >= 0))
        {
            throw new IllegalStateException("byteOffset must be >= 0");
        }
        if (!(byteBound <= buf.length))
        {
            throw new IllegalStateException("byteOffset + byteLength must be <= buf.size");
        }
    }

    public byte[] getBuf()
    {
        return buf;
    }

    /** Clone with the current state (offset) and a new length in bytes. */
    public BitReader clone(int newByteLength)
    {
        if (!(offset % 8 == 0))
        {
            throw new IllegalStateException("Cannot clone BitReader with unaligned offset");
        }
        if (!(offset / 8 + newByteLength <= byteBound))
        {
            throw new IllegalStateException(
                "newByteLength " + newByteLength + " exceeds buffer length " + byteLength +
                    " after offset " + byteOffset
            );
        }
        return new BitReader(buf, offset / 8, newByteLength);
    }

    public int remainingBits()
    {
        return byteBound * 8 - offset;
    }

    /** Read a single bit from the buffer, as a boolean, incrementing the offset. */
    public boolean bitAsBoolean()
    {
        int byteIdx = offset / 8;
        int bitIdx = offset % 8;
        if (!(byteIdx < byteBound))
        {
            throw new IllegalStateException(
                "offset " + offset + " (" + byteIdx + "/" + bitIdx + ") invalid in buffer of length " +
                    byteLength + " after offset " + byteOffset
            );
        }
        byte b = buf[byteIdx];
        byte mask = (byte) (1 << (7 - bitIdx));
        offset++;

        return (b & mask) != 0;
    }

    /** Read a single bit from the buffer, as an integer, incrementing the offset. */
    public int bit()
    {
        return bitAsBoolean() ? 1 : 0;
    }

    /** Read {@code n} bits from the buffer, returning them as an unsigned integer. */
    public int bits(int n)
    {
        if (!(n < Integer.SIZE))
        {
            throw new IllegalArgumentException();
        }

        int ret = 0;

        /* TODO: optimize this */
        for (int i = 0; i < n; i++)
        {
            ret = ret << 1;
            ret = ret | bit();
        }

        return ret;
    }

    /** Read {@code n} bits from the buffer, returning them as an unsigned long. */
    public long bitsLong(int n)
    {
        if (!(n < Long.SIZE))
        {
            throw new IllegalArgumentException();
        }

        long ret = 0L;

        /* TODO: optimize this */
        for (int i = 0; i < n; i++)
        {
            ret = ret << 1;
            ret = ret | (long) bit();
        }

        return ret;
    }

    /** Skip forward {@code n} bits in the buffer. */
    public void skipBits(int n)
    {
        offset += n;
    }

    /** Read a non-symmetric unsigned integer with max *value* {@code n} from the buffer.
     * (Note: *not* the number of bits.)
     *  See https://aomediacodec.github.io/av1-rtp-spec/#a82-syntax
     */
    public int ns(int n)
    {
        int w = 0;
        int x = n;
        while (x != 0)
        {
            x = x >> 1;
            w++;
        }
        int m = (1 << w) - n;
        int v = bits(w - 1);
        if (v < m)
        {
            return v;
        }
        int extraBit = bit();
        return (v << 1) - m + extraBit;
    }

    /**
     * Read a LEB128-encoded unsigned integer.
     * https://aomediacodec.github.io/av1-spec/#leb128
     */
    public long leb128()
    {
        long value = 0L;
        for (int i = 0; i <= 8; i++)
        {
            boolean hasNext = bitAsBoolean();
            value = value | (((long) bits(7)) << (i * 7));
            if (!hasNext)
            {
                return value;
            }
        }
        return value;
    }

    /** Reset the reader to the beginning of the buffer */
    public void reset()
    {
        offset = byteOffset * 8;
    }
}
