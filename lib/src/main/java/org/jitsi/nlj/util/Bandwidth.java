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

/**
 * {@link Bandwidth} models a current bandwidth, represented as a rate of bits per second.
 *
 * (Deviation: upstream is a Kotlin {@code value class} with a family of {@code Int/Float/Double/Long} extension
 * properties ({@code .bps}, {@code .kbps}, {@code .mbps}, {@code .bytesPerSec}) for constructing instances. Java has
 * no extension properties, so those become the {@code of*(long)}/{@code of*(double)} static factories below.)
 */
public final class Bandwidth implements Comparable<Bandwidth>
{
    public static final Bandwidth INFINITY = new Bandwidth(Long.MAX_VALUE);
    public static final Bandwidth ZERO = new Bandwidth(0L);
    public static final Bandwidth MINUS_INFINITY = new Bandwidth(Long.MIN_VALUE);

    private final long bps;

    public Bandwidth(long bps)
    {
        this.bps = bps;
    }

    public Bandwidth(double bps)
    {
        this.bps = (long) bps;
    }

    public long getBps()
    {
        return bps;
    }

    public double getBytesPerSec()
    {
        return bps / 8.0;
    }

    public double getKbps()
    {
        return bps / 1000.0;
    }

    public double getMbps()
    {
        return bps / (1000.0 * 1000.0);
    }

    public Bandwidth minus(Bandwidth other)
    {
        return new Bandwidth(bps - other.bps);
    }

    public Bandwidth plus(Bandwidth other)
    {
        return new Bandwidth(bps + other.bps);
    }

    /**
     * For multiplication, we support multiplying against a normal number (not another bandwidth). This allows
     * applying some factor to a given bandwidth, for example {@code currentBandwidth.times(0.95)} to reduce
     * {@code currentBandwidth} by 5%.
     */
    public Bandwidth times(double other)
    {
        return new Bandwidth(Math.round(bps * other));
    }

    public Bandwidth times(int other)
    {
        return new Bandwidth(bps * other);
    }

    /**
     * Create a {@link DataSize} from this {@link Bandwidth} times a {@link Duration}.
     */
    public DataSize times(Duration duration)
    {
        return DataSize.ofBits(Math.round(bps * DurationKt.toDouble(duration)));
    }

    /**
     * For division, we support both dividing by a normal number (giving a bandwidth), and dividing by another
     * bandwidth, giving a number.
     */
    public Bandwidth div(double other)
    {
        return new Bandwidth(Math.round(bps / other));
    }

    public Bandwidth div(int other)
    {
        return new Bandwidth(bps / other);
    }

    public double div(Bandwidth other)
    {
        return (double) bps / (double) other.bps;
    }

    @Override
    public int compareTo(Bandwidth other)
    {
        return Long.compare(bps, other.bps);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof Bandwidth))
        {
            return false;
        }
        return bps == ((Bandwidth) o).bps;
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(bps);
    }

    @Override
    public String toString()
    {
        // To determine which unit we'll print in, find the biggest one which has a value in the ones place
        DecimalFormat format = new DecimalFormat("0.##");
        if (getMbps() >= 1)
        {
            return format.format(getMbps()) + " mbps";
        }
        else if (getKbps() >= 1)
        {
            return format.format(getKbps()) + " kbps";
        }
        else
        {
            return format.format(bps) + " bps";
        }
    }

    public boolean isInfinite()
    {
        return equals(INFINITY) || equals(MINUS_INFINITY);
    }

    public boolean isFinite()
    {
        return !isInfinite();
    }

    /**
     * Ensures that this value is not greater than the specified {@code maximumValue}.
     *
     * @return this value if it's less than or equal to {@code maximumValue}, or {@code maximumValue} otherwise.
     */
    public Bandwidth coerceAtMost(Bandwidth maximumValue)
    {
        return compareTo(maximumValue) > 0 ? maximumValue : this;
    }

    public static Bandwidth fromString(String str)
    {
        StringBuilder digits = new StringBuilder();
        StringBuilder notDigits = new StringBuilder();
        for (int i = 0; i < str.length(); i++)
        {
            char c = str.charAt(i);
            if (Character.isDigit(c))
            {
                digits.append(c);
            }
            else
            {
                notDigits.append(c);
            }
        }
        int amount = Integer.parseInt(digits.toString());
        String unit = notDigits.toString().trim().toLowerCase();
        switch (unit)
        {
            case "bps":
                return ofBps(amount);
            case "kbps":
                return ofKbps(amount);
            case "mbps":
                return ofMbps(amount);
            default:
                throw new IllegalArgumentException("Unrecognized unit " + unit);
        }
    }

    public static Bandwidth ofBps(long bps)
    {
        return new Bandwidth(bps);
    }

    public static Bandwidth ofBps(double bps)
    {
        return new Bandwidth(bps);
    }

    public static Bandwidth ofBytesPerSec(long bytesPerSec)
    {
        return new Bandwidth(bytesPerSec * 8L);
    }

    public static Bandwidth ofBytesPerSec(double bytesPerSec)
    {
        return new Bandwidth(bytesPerSec * 8);
    }

    public static Bandwidth ofKbps(long kbps)
    {
        return new Bandwidth(kbps * 1000L);
    }

    public static Bandwidth ofKbps(double kbps)
    {
        return new Bandwidth(kbps * 1000);
    }

    public static Bandwidth ofMbps(long mbps)
    {
        return new Bandwidth(mbps * 1000L * 1000L);
    }

    public static Bandwidth ofMbps(double mbps)
    {
        return new Bandwidth(mbps * 1000 * 1000);
    }

    /**
     * Returns the sum of all elements in the collection.
     */
    public static Bandwidth sum(Iterable<Bandwidth> bandwidths)
    {
        Bandwidth result = null;
        for (Bandwidth b : bandwidths)
        {
            result = (result == null) ? b : result.plus(b);
        }
        if (result == null)
        {
            throw new UnsupportedOperationException("Empty collection can't be reduced.");
        }
        return result;
    }

    /**
     * Returns the maximum of two {@link Bandwidth}s.
     */
    public static Bandwidth max(Bandwidth a, Bandwidth b)
    {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /**
     * Returns the minimum of two {@link Bandwidth}s.
     */
    public static Bandwidth min(Bandwidth a, Bandwidth b)
    {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
