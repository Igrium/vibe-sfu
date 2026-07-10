/*
 * Copyright @ 2020 - Present, 8x8 Inc
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

import org.jitsi.utils.stats.RateTracker;

import java.time.Clock;
import java.time.Duration;

public class BitrateTracker
{
    private static final Duration DEFAULT_BUCKET_SIZE = Duration.ofMillis(1);

    // Use composition to expose functions with the data types we want ([DataSize], [Bandwidth]) and not the raw
    // types that RateTracker uses.
    private final RateTracker tracker;
    private final Clock clock;

    public BitrateTracker(Duration windowSize)
    {
        this(windowSize, DEFAULT_BUCKET_SIZE, Clock.systemUTC());
    }

    public BitrateTracker(Duration windowSize, Duration bucketSize)
    {
        this(windowSize, bucketSize, Clock.systemUTC());
    }

    public BitrateTracker(Duration windowSize, Duration bucketSize, Clock clock)
    {
        this.tracker = new RateTracker(windowSize, bucketSize, clock);
        this.clock = clock;
    }

    public Bandwidth getRate()
    {
        return getRate(clock.millis());
    }

    public Bandwidth getRate(long nowMs)
    {
        return new Bandwidth(tracker.getRate(nowMs));
    }

    public long getRateBps()
    {
        return getRateBps(clock.millis());
    }

    public long getRateBps(long nowMs)
    {
        return tracker.getRate(nowMs);
    }

    public void update(DataSize dataSize)
    {
        update(dataSize, clock.millis());
    }

    public void update(DataSize dataSize, long now)
    {
        tracker.update(dataSize.getBits(), now);
    }

    public DataSize getAccumulatedSize()
    {
        return getAccumulatedSize(clock.millis());
    }

    public DataSize getAccumulatedSize(long now)
    {
        return new DataSize(tracker.getAccumulatedCount(now));
    }
}
