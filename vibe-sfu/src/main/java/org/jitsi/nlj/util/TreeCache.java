/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Implements a cache based on integer values, optimized for sparse values, such that you can find the most
 * recent cached value before a specific value.
 *
 * The intended use case is AV1 Dependency Descriptor history.
 */
public class TreeCache<T>
{
    private final int minSize;

    private final TreeMap<Long, T> map = new TreeMap<>();

    private long highestIndex = -1L;

    public TreeCache(int minSize)
    {
        this.minSize = minSize;
    }

    public void insert(long index, T value)
    {
        map.put(index, value);

        updateState(index);
    }

    public Map.Entry<Long, T> getEntryBefore(long index)
    {
        updateState(index);
        return map.floorEntry(index);
    }

    public T get(long index)
    {
        return map.get(index);
    }

    private void updateState(long index)
    {
        if (highestIndex < index)
        {
            highestIndex = index;
        }

        /* Keep at most one entry older than highestIndex - minSize. */
        NavigableMap<Long, T> headMap = map.headMap(highestIndex - minSize, false);
        if (headMap.size() > 1)
        {
            long last = headMap.lastKey();
            Iterator<Long> iter = headMap.keySet().iterator();
            while (iter.hasNext())
            {
                if (iter.next() < last)
                {
                    iter.remove();
                }
            }
        }
    }

    public int size()
    {
        return map.size();
    }
}
