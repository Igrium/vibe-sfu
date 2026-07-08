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

import java.util.Arrays;

/**
 * Write individual bits, and unaligned sets of bits, to a {@code byte[]}, with an incrementing offset.
 */
public class BitWriter
{
    private final byte[] buf;
    private final int byteOffset;
    private final int byteLength;
    private int offset;
    private final int byteBound;

    public BitWriter(byte[] buf)
    {
        this(buf, 0, buf.length);
    }

    public BitWriter(byte[] buf, int byteOffset)
    {
        this(buf, byteOffset, buf.length);
    }

    public BitWriter(byte[] buf, int byteOffset, int byteLength)
    {
        this.buf = buf;
        this.byteOffset = byteOffset;
        this.byteLength = byteLength;
        this.offset = byteOffset * 8;
        this.byteBound = byteOffset + byteLength;

        Arrays.fill(buf, byteOffset, byteBound, (byte) 0);
    }

    public byte[] getBuf()
    {
        return buf;
    }

    public int getByteOffset()
    {
        return byteOffset;
    }

    public void writeBit(boolean value)
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

        if (value)
        {
            buf[byteIdx] = (byte) (buf[byteIdx] | (1 << (7 - bitIdx)));
        }
        offset++;
    }

    public void writeBits(int bits, int value)
    {
        if (!(value < (1 << bits)))
        {
            throw new IllegalStateException("value " + value + " cannot be represented in " + bits + " bits");
        }
        for (int i = 0; i < bits; i++)
        {
            writeBit((value & (1 << (bits - i - 1))) != 0);
        }
    }

    public void writeNs(int n, int v)
    {
        if (n == 1)
        {
            return;
        }
        int w = 0;
        int x = n;
        while (x != 0)
        {
            x = x >> 1;
            w++;
        }
        int m = (1 << w) - n;
        if (v < m)
        {
            writeBits(w - 1, v);
        }
        else
        {
            writeBits(w, v + m);
        }
    }

    public int getRemainingBits()
    {
        return byteBound * 8 - offset;
    }
}
