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
package org.jitsi.videobridge.transport.ice;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.utils.stats.BucketStats;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Keep ICE-related statistics (currently only RTT).
 */
public class IceStatistics
{
    public static final IceStatistics stats = new IceStatistics();

    /** Stats for all harvesters */
    private final Stats combinedStats = new Stats();

    /** Stats by harvester name. */
    private final ConcurrentHashMap<String, Stats> statsByHarvesterName = new ConcurrentHashMap<>();

    /** Add a round-trip-time measurement for a specific harvester */
    public void add(String harvesterName, double rttMs)
    {
        combinedStats.add(rttMs);
        statsByHarvesterName.computeIfAbsent(harvesterName, k -> new Stats()).add(rttMs);
    }

    public ObjectNode toJson()
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.set("all", combinedStats.toJson());
        statsByHarvesterName.forEach((harvesterName, stats) -> o.set(harvesterName, stats.toJson()));
        return o;
    }

    private static class Stats
    {
        private static final List<Long> THRESHOLDS =
            Arrays.asList(0L, 10L, 20L, 40L, 60L, 80L, 100L, 150L, 200L, 250L, 300L, 500L, 1000L, Long.MAX_VALUE);

        /** Histogram of RTTs */
        private final BucketStats buckets = new BucketStats(THRESHOLDS, "", "");
        private final DoubleAdder sum = new DoubleAdder();
        private final AtomicInteger count = new AtomicInteger();

        void add(double rttMs)
        {
            sum.add(rttMs);
            count.incrementAndGet();
            buckets.addValue((long) (rttMs + 0.5));
        }

        ObjectNode toJson()
        {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            o.put("average", sum.sum() / count.get());
            o.set("buckets", buckets.toJson());
            return o;
        }
    }
}
