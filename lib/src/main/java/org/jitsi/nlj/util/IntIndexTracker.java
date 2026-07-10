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

public class IntIndexTracker extends IndexTracker<Integer>
{
    private final int bits;

    public IntIndexTracker(int bits)
    {
        if (bits < 1 || bits > 31)
        {
            throw new IllegalArgumentException("bits must be between 1 and 31");
        }
        this.bits = bits;
    }

    public int getBits()
    {
        return bits;
    }

    @Override
    long addRollover(Integer seqNum, long roc)
    {
        return (1L << bits) * roc + seqNum;
    }

    @Override
    boolean rollsOver(Integer a, Integer b)
    {
        return isOlderThan(a, b) && b < a;
    }

    @Override
    boolean isOlderThan(Integer a, Integer b)
    {
        return delta(a, b) < 0;
    }

    @Override
    long toLong(Integer t)
    {
        return t;
    }

    @Override
    boolean isValid(Integer t)
    {
        return t >= 0 && t < (1 << bits);
    }

    private int delta(int a, int b)
    {
        int diff = a - b;
        if (diff < -(1 << (bits - 1)))
        {
            return diff + (1 << bits);
        }
        else if (diff > (1 << (bits - 1)))
        {
            return diff - (1 << bits);
        }
        return diff;
    }
}
