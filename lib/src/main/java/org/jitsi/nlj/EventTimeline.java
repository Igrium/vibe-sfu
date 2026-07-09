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
package org.jitsi.nlj;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * We want this thread safe, because while PacketInfo objects are only handled by a single thread at a time,
 * {@code StatsKeepingNode} may add an "exit" event after the packets has been added to another queue potentially
 * handled by a different thread. This is not critical as it only affects the timeline and the result is just some
 * "exit" events missing from the trace logs.
 */
public class EventTimeline implements Iterable<Map.Entry<String, Duration>>
{
    private final Clock clock;

    private final List<Map.Entry<String, Duration>> timeline;

    /**
     * The {@link #referenceTime} refers to the first timestamp we have
     * in the timeline.  In the timeline this is used as time "0" and
     * all other times are represented as deltas from this 0.
     */
    private Instant referenceTime;

    public EventTimeline()
    {
        this(new ArrayList<>(), Clock.systemUTC());
    }

    public EventTimeline(List<Map.Entry<String, Duration>> timelineArg, Clock clock)
    {
        this.timeline = Collections.synchronizedList(timelineArg);
        this.clock = clock;
    }

    public Instant getReferenceTime()
    {
        return referenceTime;
    }

    public void setReferenceTime(Instant referenceTime)
    {
        this.referenceTime = referenceTime;
    }

    public int getSize()
    {
        return timeline.size();
    }

    public void addEvent(String desc)
    {
        Instant now = clock.instant();
        if (referenceTime == null)
        {
            referenceTime = now;
        }
        timeline.add(new AbstractMap.SimpleImmutableEntry<>(desc, Duration.between(referenceTime, now)));
    }

    public EventTimeline clone()
    {
        EventTimeline clone = new EventTimeline(new ArrayList<>(timeline), clock);
        clone.referenceTime = referenceTime;
        return clone;
    }

    @Override
    public Iterator<Map.Entry<String, Duration>> iterator()
    {
        return timeline.iterator();
    }

    /**
     * Return the total time between this packet's first event and last event
     * or -1 if there is no reference time
     */
    public Duration totalDelay()
    {
        if (referenceTime != null)
        {
            synchronized (timeline)
            {
                return timeline.get(timeline.size() - 1).getValue();
            }
        }
        return Duration.ofMillis(-1);
    }

    @Override
    public String toString()
    {
        StringBuffer sb = new StringBuffer();
        if (referenceTime != null)
        {
            sb.append("Reference time: ").append(referenceTime).append("; ");
            synchronized (timeline)
            {
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < timeline.size(); i++)
                {
                    if (i > 0)
                    {
                        joined.append("; ");
                    }
                    joined.append(timeline.get(i));
                }
                sb.append(joined);
            }
        }
        else
        {
            sb.append("[No timeline]");
        }
        return sb.toString();
    }
}
