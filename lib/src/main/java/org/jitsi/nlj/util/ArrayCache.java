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
import org.jitsi.nlj.transform.NodeStatsProducer;

import java.time.Clock;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements a fixed-sized cache based on a pre-filled array. The main use-case is the outgoing RTP packet cache.
 *
 * @author Boris Grozev
 */
public class ArrayCache<T> implements NodeStatsProducer
{
    private final int size;

    /**
     * The function to use to clone items. The cache always saves copies of the items that are inserted.
     */
    private final Function<T, T> cloneItem;

    protected final boolean synchronize;

    protected final Clock clock;

    private final Container<T>[] cache;

    protected final Object syncRoot = new Object();

    /**
     * The index in {@link #cache} where the item with the highest index is stored.
     */
    private int head = -1;

    private int numInserts = 0;
    private int numOldInserts = 0;
    private final AtomicInteger numHitsCounter = new AtomicInteger();
    private final AtomicInteger numMissesCounter = new AtomicInteger();

    public ArrayCache(int size, Function<T, T> cloneItem)
    {
        this(size, cloneItem, true, Clock.systemUTC());
    }

    public ArrayCache(int size, Function<T, T> cloneItem, boolean synchronize)
    {
        this(size, cloneItem, synchronize, Clock.systemUTC());
    }

    public ArrayCache(int size, Function<T, T> cloneItem, boolean synchronize, Clock clock)
    {
        this.size = size;
        this.cloneItem = cloneItem;
        this.synchronize = synchronize;
        this.clock = clock;
        // (Deviation: upstream's Container is a Kotlin `inner class`, ported below to a static generic nested
        // class -- Java disallows creating an array of a non-static generic inner class (generic array creation).)
        @SuppressWarnings("unchecked")
        Container<T>[] newCache = new Container[size];
        this.cache = newCache;
        for (int i = 0; i < size; i++)
        {
            cache[i] = new Container<>(null, -1, -1);
        }
    }

    public int getSize()
    {
        return size;
    }

    public int getNumInserts()
    {
        return numInserts;
    }

    public int getNumOldInserts()
    {
        return numOldInserts;
    }

    public int getNumHits()
    {
        return numHitsCounter.get();
    }

    public int getNumMisses()
    {
        return numMissesCounter.get();
    }

    public double getHitRate()
    {
        return numHitsCounter.get() * 1.0 / Math.max(1, numHitsCounter.get() + numMissesCounter.get());
    }

    protected long getLastIndex()
    {
        return head == -1 ? -1 : cache[head].index;
    }

    public boolean isEmpty()
    {
        return head == -1;
    }

    /**
     * Inserts an item with a specific index in the cache. Stores a copy.
     */
    public boolean insertItem(T item, long index, long timeAdded)
    {
        if (synchronize)
        {
            synchronized (syncRoot)
            {
                return doInsert(item, index, timeAdded);
            }
        }
        else
        {
            return doInsert(item, index, timeAdded);
        }
    }

    /**
     * Inserts an item with a specific index in the cache, computing time from {@link #clock}. Stores a copy.
     */
    public boolean insertItem(T item, long index)
    {
        return insertItem(item, index, clock.millis());
    }

    private int position(long diff)
    {
        return (int) Util.floorMod(head + diff, (long) size);
    }

    private boolean doInsert(T item, long index, long timeAdded)
    {
        int position;
        if (head == -1)
        {
            head = 0;
            position = head;
        }
        else
        {
            long diff = index - cache[head].index;
            if (diff <= -size)
            {
                // The item is too old
                numOldInserts++;
                return false;
            }
            else if (diff < 0)
            {
                position = position(diff);
            }
            else
            {
                head = position(diff);
                position = head;
            }
        }

        numInserts++;
        if (cache[position].item != null)
        {
            discardItem(cache[position].item);
        }
        cache[position].item = cloneItem.apply(item);
        cache[position].index = index;
        cache[position].timeAdded = timeAdded;
        return true;
    }

    /**
     * Called when an item in the cache is replaced/discarded.
     */
    protected void discardItem(T item)
    {
    }

    /**
     * Gets an item from the cache with a given index. Returns {@code null} if there is no item with this index in
     * the cache. The item is wrapped in a {@link Container} to allow access to the time it was added to the cache,
     * and we provide a copy.
     */
    public Container<T> getContainer(long index)
    {
        return getContainer(index, true);
    }

    public Container<T> getContainer(long index, boolean shouldCloneItem)
    {
        Container<T> result;
        if (synchronize)
        {
            synchronized (syncRoot)
            {
                result = doGet(index, shouldCloneItem);
            }
        }
        else
        {
            result = doGet(index, shouldCloneItem);
        }

        if (result != null)
        {
            numHitsCounter.incrementAndGet();
        }
        else
        {
            numMissesCounter.incrementAndGet();
        }
        return result;
    }

    private Container<T> doGet(long index, boolean shouldCloneItem)
    {
        if (index < 0)
        {
            return null;
        }
        if (head == -1)
        {
            // Not initialized (empty), or newer than head.
            return null;
        }

        long diff = index - cache[head].index;
        if (diff > 0)
        {
            // The requested index is newer than the last index we have.
            return null;
        }

        int position = position(diff);
        if (cache[position].index == index)
        {
            return cloneContainer(cache[position], shouldCloneItem);
        }
        return null;
    }

    private Container<T> cloneContainer(Container<T> container, boolean shouldCloneItem)
    {
        T clonedItem = container.item == null ? null : (shouldCloneItem ? cloneItem.apply(container.item) : container.item);
        return new Container<>(clonedItem, container.index, container.timeAdded);
    }

    /**
     * Checks whether the cache contains an item with a given index.
     */
    public boolean containsIndex(long index)
    {
        if (synchronize)
        {
            synchronized (syncRoot)
            {
                return doContains(index);
            }
        }
        else
        {
            return doContains(index);
        }
    }

    private boolean doContains(long index)
    {
        if (head == -1)
        {
            return false;
        }

        long diff = index - cache[head].index;
        if (diff > 0)
        {
            return false;
        }

        int position = position(diff);
        return cache[position].index == index;
    }

    /**
     * Updates the {@code timeAdded} value of an item with a particular index, if it is in the cache.
     */
    protected void updateTimeAdded(long index, long timeAdded)
    {
        if (synchronize)
        {
            synchronized (syncRoot)
            {
                doUpdateTimeAdded(index, timeAdded);
            }
        }
        else
        {
            doUpdateTimeAdded(index, timeAdded);
        }
    }

    private void doUpdateTimeAdded(long index, long timeAdded)
    {
        if (head == -1 || index > cache[head].index)
        {
            return;
        }
        long diff = index - cache[head].index;
        int position = position(diff);
        if (cache[position].index == index)
        {
            cache[position].timeAdded = timeAdded;
        }
    }

    /**
     * Iterates from the last index added to the cache down at most {@link #size} elements. For each item, if it is
     * in the last 'window', calls {@code predicate} with the item. If {@code predicate} returns {@code false} for
     * any item, stops the iteration and returns. Note that the caller must clone the item on their own if they want
     * to keep or modify it in any way.
     */
    public void forEachDescending(java.util.function.Predicate<T> predicate)
    {
        if (synchronize)
        {
            synchronized (syncRoot)
            {
                doForEachDescending(predicate);
            }
        }
        else
        {
            doForEachDescending(predicate);
        }
    }

    private void doForEachDescending(java.util.function.Predicate<T> predicate)
    {
        if (head == -1)
        {
            return;
        }

        long highIndex = cache[head].index;
        long lowIndex = Math.max(cache[head].index - size, 1L);
        for (int i = 0; i < size; i++)
        {
            int position = (int) Util.floorMod(head - i, size);
            long index = cache[position].index;
            if (index >= lowIndex && index <= highIndex)
            {
                // We maintain the invariant [index==-1 iff item==null]
                if (!predicate.test(cache[position].item))
                {
                    return;
                }
            }
        }
    }

    /**
     * Removes all items stored in the cache, calling {@link #discardItem(Object)} for each one.
     */
    public void flush()
    {
        if (synchronize)
        {
            synchronized (syncRoot)
            {
                doFlush();
            }
        }
        else
        {
            doFlush();
        }
    }

    private void doFlush()
    {
        for (Container<T> container : cache)
        {
            container.index = -1;
            if (container.item != null)
            {
                discardItem(container.item);
            }
            container.item = null;
            container.timeAdded = -1;
        }
        head = -1;
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = new NodeStatsBlock("ArrayCache");
        block.addNumber("size", size);
        block.addNumber("numInserts", numInserts);
        block.addNumber("numOldInserts", numOldInserts);
        block.addNumber("numHits", getNumHits());
        block.addNumber("numMisses", getNumMisses());
        block.addNumber("numRequests", getNumHits() + getNumMisses());
        NodeStatsBlockExtensions.addRatio(block, "hitRate", "numHits", "numRequests", 1);
        return block;
    }

    /**
     * (Deviation: upstream's {@code Container} is a Kotlin {@code inner class} (so its {@code clone()} can reach
     * the outer {@code cloneItem} function directly). Ported here as a {@code static} generic nested class --
     * required so {@link ArrayCache} can allocate a {@code Container<T>[]} array (Java forbids arrays of non-static
     * generic inner classes) -- with the clone logic moved to {@link ArrayCache#cloneContainer}.)
     */
    public static class Container<T>
    {
        public T item;
        public long index;
        public long timeAdded;

        public Container(T item, long index, long timeAdded)
        {
            this.item = item;
            this.index = index;
            this.timeAdded = timeAdded;
        }
    }
}
