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

import java.time.Duration;

/**
 * Ported from the upstream Kotlin top-level (extension) functions in {@code RateUtils.kt}.
 */
public class RateUtils
{
    /**
     * This method isn't technically necessary, but provides better readability, i.e.:
     *
     * {@code Duration time = atRate(howLongToSend(DataSize.ofMegabytes(1)), Bandwidth.ofMbps(1));}
     *
     * as opposed to
     *
     * {@code Duration time = atRate(DataSize.ofMegabytes(1), Bandwidth.ofMbps(1));}
     */
    public static DataSize howLongToSend(DataSize size)
    {
        return size;
    }

    /**
     * (Deviation: upstream defines this as the infix extension function {@code DataSize.atRate(Bandwidth)}.)
     */
    public static Duration atRate(DataSize size, Bandwidth bw)
    {
        double bitsPerNano = bw.getBps() / 1e9;
        return Duration.ofNanos((long) (size.getBits() / bitsPerNano));
    }

    public static Bandwidth howMuchCanISendAtRate(Bandwidth bw)
    {
        return bw;
    }

    /**
     * (Deviation: upstream names this infix function {@code in}, which is a reserved keyword in Java.)
     */
    public static DataSize inTime(Bandwidth bandwidth, Duration time)
    {
        return new DataSize((long) (bandwidth.getBps() * (time.getSeconds() + time.getNano() / 1e9)));
    }
}
