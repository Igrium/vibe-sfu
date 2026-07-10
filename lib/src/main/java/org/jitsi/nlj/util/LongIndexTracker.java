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

package org.jitsi.nlj.util;

public class LongIndexTracker extends IndexTracker<Long>
{
    private final int bits;

    public LongIndexTracker(int bits)
    {
        if (bits < 1 || bits > 63)
        {
            throw new IllegalArgumentException("bits must be between 1 and 63");
        }
        this.bits = bits;
    }

    public int getBits()
    {
        return bits;
    }

    @Override
    long addRollover(Long seqNum, long roc)
    {
        return (1L << bits) * roc + seqNum;
    }

    @Override
    boolean rollsOver(Long a, Long b)
    {
        return isOlderThan(a, b) && b < a;
    }

    @Override
    boolean isOlderThan(Long a, Long b)
    {
        return delta(a, b) < 0;
    }

    @Override
    long toLong(Long t)
    {
        return t;
    }

    @Override
    boolean isValid(Long t)
    {
        return t >= 0L && t < (1L << bits);
    }

    private long delta(long a, long b)
    {
        long diff = a - b;
        if (diff < -(1L << (bits - 1)))
        {
            return diff + (1L << bits);
        }
        else if (diff > (1L << (bits - 1)))
        {
            return diff - (1L << bits);
        }
        return diff;
    }
}
