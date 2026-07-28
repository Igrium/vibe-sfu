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

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * A cache which holds an arbitrary {@code DataType} and stores it, as well
 * as the time at which it was added to the cache. On updates, it will
 * check to prune itself to remove anything older than the configured
 * timeout.
 * NOTE: it's possible the pruning does not remove ALL elements older
 * than the configured timeout as we store the elements according to
 * the value of their {@code IndexType}, not their insertion timestamp.
 * This is because retrieval is much handier when done via the value
 * of {@code IndexType}. This means that it's possible older values will
 * not be pruned when expected, but if the ordering of {@code IndexType}
 * roughly follows the insertion order, it should not be too bad.
 */
public class TimeExpiringCache<IndexType, DataType>
{
    /**
     * The amount of time after which an element should be pruned from the cache
     */
    private final Duration dataTimeout;

    /**
     * The maximum amount of elements we'll allow in the cache
     */
    private final int maxNumElements;

    private final Clock clock;

    private final TreeMap<IndexType, Container<DataType>> cache = new TreeMap<>();

    public TimeExpiringCache(Duration dataTimeout, int maxNumElements)
    {
        this(dataTimeout, maxNumElements, Clock.systemUTC());
    }

    public TimeExpiringCache(Duration dataTimeout, int maxNumElements, Clock clock)
    {
        this.dataTimeout = dataTimeout;
        this.maxNumElements = maxNumElements;
        this.clock = clock;
    }

    public void insert(IndexType index, DataType data)
    {
        Container<DataType> container = new Container<>(data, clock.millis());
        synchronized (cache)
        {
            cache.put(index, container);
            clean(clock.millis() - dataTimeout.toMillis());
        }
    }

    public DataType get(IndexType index)
    {
        synchronized (cache)
        {
            Container<DataType> container = cache.get(index);
            return container == null ? null : container.data;
        }
    }

    /**
     * For each element in this cache, in descending order of the value of their
     * {@code IndexType}, run the given {@code predicate}. If {@code predicate} returns false, stop
     * the iteration.
     */
    public void forEachDescending(Predicate<DataType> predicate)
    {
        synchronized (cache)
        {
            Iterator<Map.Entry<IndexType, Container<DataType>>> iter = cache.descendingMap().entrySet().iterator();
            while (iter.hasNext())
            {
                Container<DataType> container = iter.next().getValue();
                if (!predicate.test(container.data))
                {
                    break;
                }
            }
        }
    }

    private void clean(long expirationTimestamp)
    {
        synchronized (cache)
        {
            Iterator<Map.Entry<IndexType, Container<DataType>>> iter = cache.entrySet().iterator();
            while (iter.hasNext())
            {
                Container<DataType> container = iter.next().getValue();
                if (cache.size() > maxNumElements)
                {
                    iter.remove();
                }
                else if (container.timeAdded <= expirationTimestamp)
                {
                    iter.remove();
                }
                else
                {
                    // We'll break out of the loop once we find an insertion time that
                    // is not older than the expiration cutoff
                    break;
                }
            }
        }
    }

    /**
     * Holds an instance of {@code Type} as well as when it was
     * added to the data structure so that we can time out
     * old {@link Container}s
     */
    private static class Container<Type>
    {
        Type data;
        long timeAdded;

        Container(Type data, long timeAdded)
        {
            this.data = data;
            this.timeAdded = timeAdded;
        }
    }
}
