/*
 * Copyright @ 2019 - present 8x8, Inc.
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

import org.jitsi.nlj.stats.NodeStatsBlock;

import java.time.Duration;

public class NodeStatsBlockExtensions
{
    /**
     * Adds a compound value which computes the bitrate in megabits per second given two values
     * that represent number of bytes and duration in milliseconds.
     *
     * @param name the name of the new value to add.
     * @param bytesKey the name of the stat which has the number of bytes.
     * @param durationMsKey the name of the stat which has the duration in milliseconds.
     */
    public static void addMbps(NodeStatsBlock block, String name, String bytesKey, String durationMsKey)
    {
        block.addCompoundValue(name, it -> Util.getMbps(
            it.getNumberOrDefault(bytesKey, 0),
            Duration.ofMillis(it.getNumberOrDefault(durationMsKey, 1).longValue())
        ));
    }

    /**
     * Adds a compound value which computes the ratio of two values.
     *
     * @param name the name of the new value to add.
     * @param numeratorKey the name of the stat which holds the numerator of the ratio.
     * @param denominatorKey the name of the stat which holds the denominator of the ratio.
     * @param defaultDenominator default value for the denominator in case the value with the given key
     * doesn't exist or is 0.
     */
    public static void addRatio(NodeStatsBlock block, String name, String numeratorKey, String denominatorKey, Number defaultDenominator)
    {
        block.addCompoundValue(name, it -> {
            Number numerator = it.getNumber(numeratorKey);
            if (numerator == null)
            {
                numerator = 0;
            }
            Number denominator = it.getNumber(denominatorKey);
            if (denominator == null)
            {
                denominator = defaultDenominator;
            }
            double denominatorValue = denominator.doubleValue() == 0.0 ? defaultDenominator.doubleValue() : denominator.doubleValue();
            return numerator.doubleValue() / denominatorValue;
        });
    }

    public static void addRatio(NodeStatsBlock block, String name, String numeratorKey, String denominatorKey)
    {
        addRatio(block, name, numeratorKey, denominatorKey, 1);
    }
}
