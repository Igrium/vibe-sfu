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

import org.jitsi.utils.DurationKt;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.Objects;

/**
 * Model an amount of data, internally represented as a number of bits.
 *
 * (Deviation: upstream also defines {@code Int/Long} extension properties ({@code .bits}, {@code .bytes},
 * {@code .kilobytes}, {@code .megabytes}) for constructing instances. Java has no extension properties, so those
 * become the {@code of*(long)} static factories below.)
 */
public final class DataSize implements Comparable<DataSize>
{
    public static final DataSize ZERO = new DataSize(0);
    public static final DataSize INFINITY = new DataSize(Long.MAX_VALUE);

    private final long bits;

    public DataSize(long bits)
    {
        this.bits = bits;
    }

    public long getBits()
    {
        return bits;
    }

    public double getBytes()
    {
        return bits / 8.0;
    }

    public double getKiloBytes()
    {
        return getBytes() / 1000.0;
    }

    public double getMegaBytes()
    {
        return getKiloBytes() / 1000.0;
    }

    public DataSize minus(DataSize other)
    {
        return new DataSize(bits - other.bits);
    }

    public DataSize plus(DataSize other)
    {
        return new DataSize(bits + other.bits);
    }

    public DataSize times(int other)
    {
        return new DataSize(bits * other);
    }

    public DataSize times(double other)
    {
        return new DataSize(Math.round(bits * other));
    }

    public DataSize div(double other)
    {
        return new DataSize(Math.round(bits / other));
    }

    public double div(DataSize other)
    {
        return (double) bits / (double) other.bits;
    }

    /**
     * Create a {@link Duration} from this {@link DataSize} divided by a {@link Bandwidth}.
     */
    public Duration div(Bandwidth bandwidth)
    {
        return Duration.ofNanos(Math.round((bits / (double) bandwidth.getBps()) * 1e9));
    }

    /**
     * Create a {@link Bandwidth} from this {@link DataSize} over a given time.
     */
    public Bandwidth per(Duration duration)
    {
        return new Bandwidth((bits * 1_000_000) / DurationKt.toRoundedMicros(duration));
    }

    @Override
    public String toString()
    {
        // To determine which unit we'll print in, find the biggest one which has a value in the ones place
        DecimalFormat format = new DecimalFormat("0.##");
        if (getMegaBytes() >= 1)
        {
            return format.format(getMegaBytes()) + " MB";
        }
        else if (getKiloBytes() >= 1)
        {
            return format.format(getKiloBytes()) + " KB";
        }
        else if (getBytes() >= 1)
        {
            return format.format(getBytes()) + " B";
        }
        else
        {
            return format.format(bits) + " bits";
        }
    }

    @Override
    public int compareTo(DataSize other)
    {
        return Long.compare(bits, other.bits);
    }

    @Override
    public boolean equals(Object other)
    {
        if (!(other instanceof DataSize))
        {
            return false;
        }
        return compareTo((DataSize) other) == 0;
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(bits);
    }

    public DataSize toWholeBytes()
    {
        return ofBytes(Math.round(getBytes()));
    }

    public static DataSize ofBits(long bits)
    {
        return new DataSize(bits);
    }

    public static DataSize ofBytes(long bytes)
    {
        return new DataSize(bytes * 8);
    }

    public static DataSize ofKilobytes(long kilobytes)
    {
        return new DataSize(kilobytes * 1000 * 8);
    }

    public static DataSize ofMegabytes(long megabytes)
    {
        return new DataSize(megabytes * 1000 * 1000 * 8);
    }

    /**
     * Returns the maximum of two {@link DataSize}s.
     */
    public static DataSize max(DataSize a, DataSize b)
    {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Returns the minimum of two {@link DataSize}s.
     */
    public static DataSize min(DataSize a, DataSize b)
    {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
