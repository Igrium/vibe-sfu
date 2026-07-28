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

package org.jitsi.nlj.stats;

import org.jitsi.utils.stats.BucketStats;

import java.util.Arrays;
import java.util.List;

public class DelayStats extends BucketStats
{
    public static final List<Long> defaultThresholds = Arrays.asList(0L, 5L, 50L, 500L, Long.MAX_VALUE);

    public DelayStats()
    {
        this(defaultThresholds);
    }

    public DelayStats(List<Long> thresholds)
    {
        super(thresholds, "_delay_ms", "_ms");
    }

    public void addDelay(long delayMs)
    {
        addValue(delayMs);
    }
}
