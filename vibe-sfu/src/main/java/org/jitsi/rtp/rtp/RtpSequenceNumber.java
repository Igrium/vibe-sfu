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

import org.jitsi.rtp.util.RtpUtils;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * A class representing an RTP sequence number.  The class operates just like
 * an {@code int} but takes rollover into account for all operations.
 *
 * This constructor assumes that the value is already coerced. Use {@link #toRtpSequenceNumber(int)} to create
 * instances.
 */
public final class RtpSequenceNumber implements Comparable<RtpSequenceNumber>
{
    public static final RtpSequenceNumber INVALID = new RtpSequenceNumber(-1);

    private final int value;

    RtpSequenceNumber(int value)
    {
        this.value = value;
    }

    public int getValue()
    {
        return value;
    }

    public static RtpSequenceNumber toRtpSequenceNumber(int value)
    {
        return new RtpSequenceNumber(value & 0xffff);
    }

    public RtpSequenceNumber plus(int num)
    {
        return toRtpSequenceNumber(value + num);
    }

    public RtpSequenceNumber plus(RtpSequenceNumber seqNum)
    {
        return toRtpSequenceNumber(value + seqNum.value);
    }

    public RtpSequenceNumber minus(int num)
    {
        return plus(-num);
    }

    public RtpSequenceNumber minus(RtpSequenceNumber seqNum)
    {
        return plus(-seqNum.value);
    }

    @Override
    public int compareTo(RtpSequenceNumber other)
    {
        return RtpUtils.getSequenceNumberDelta(value, other.value);
    }

    public RtpSequenceNumberProgression rangeTo(RtpSequenceNumber other)
    {
        return new RtpSequenceNumberProgression(this, other);
    }

    public RtpSequenceNumberProgression downTo(RtpSequenceNumber to)
    {
        return RtpSequenceNumberProgression.fromClosedRange(this, to, -1);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof RtpSequenceNumber))
        {
            return false;
        }
        RtpSequenceNumber that = (RtpSequenceNumber) o;
        return value == that.value;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(value);
    }

    @Override
    public String toString()
    {
        return "RtpSequenceNumber(value=" + value + ")";
    }

    // Copied mostly from IntProgression.
    public static final class RtpSequenceNumberProgression implements Iterable<RtpSequenceNumber>
    {
        private final RtpSequenceNumber start;
        private final RtpSequenceNumber endInclusive;
        private final int step;

        public RtpSequenceNumberProgression(RtpSequenceNumber start, RtpSequenceNumber endInclusive)
        {
            this(start, endInclusive, 1);
        }

        public RtpSequenceNumberProgression(RtpSequenceNumber start, RtpSequenceNumber endInclusive, int step)
        {
            this.start = start;
            this.endInclusive = endInclusive;
            this.step = step;
        }

        public RtpSequenceNumber getStart()
        {
            return start;
        }

        public RtpSequenceNumber getEndInclusive()
        {
            return endInclusive;
        }

        public int getStep()
        {
            return step;
        }

        @Override
        public Iterator<RtpSequenceNumber> iterator()
        {
            return new RtpSequenceNumberProgressionIterator(start, endInclusive, step);
        }

        public static RtpSequenceNumberProgression fromClosedRange(
            RtpSequenceNumber rangeStart,
            RtpSequenceNumber rangeEnd,
            int step
        )
        {
            return new RtpSequenceNumberProgression(rangeStart, rangeEnd, step);
        }
    }

    // Copied mostly from IntProgressionIterator
    public static final class RtpSequenceNumberProgressionIterator implements Iterator<RtpSequenceNumber>
    {
        private final RtpSequenceNumber finalElement;
        private final int step;
        private boolean hasNext;
        private RtpSequenceNumber next;

        public RtpSequenceNumberProgressionIterator(RtpSequenceNumber first, RtpSequenceNumber last, int step)
        {
            this.finalElement = last;
            this.step = step;
            this.hasNext = step > 0 ? first.compareTo(last) <= 0 : first.compareTo(last) >= 0;
            this.next = hasNext ? first : finalElement;
        }

        @Override
        public boolean hasNext()
        {
            return hasNext;
        }

        @Override
        public RtpSequenceNumber next()
        {
            return nextSeqNum();
        }

        public RtpSequenceNumber nextSeqNum()
        {
            RtpSequenceNumber value = next;
            if (value.equals(finalElement))
            {
                if (!hasNext)
                {
                    throw new NoSuchElementException();
                }
                hasNext = false;
            }
            else
            {
                next = next.plus(step);
            }
            return value;
        }
    }
}
