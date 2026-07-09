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

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Util
{
    public static double getMbps(Number numBytes, Duration duration)
    {
        double mbps = (numBytes.longValue() * 8.0) / (duration.toMillis() * 1000);
        return Double.isNaN(mbps) ? -1.0 : mbps;
    }

    /**
     * This method should only be called when the caller is confident the
     * contents of the iterable contain {@code Expected} types.  Because of this,
     * throwing an exception if that isn't the case is desired.
     */
    @SuppressWarnings("unchecked")
    public static <Expected> void forEachAs(Iterable<?> iterable, Consumer<Expected> action)
    {
        for (Object element : iterable)
        {
            action.accept((Expected) element);
        }
    }

    public static <Expected> void forEachIf(Iterable<?> iterable, Class<Expected> expectedClass, Consumer<Expected> action)
    {
        for (Object element : iterable)
        {
            if (expectedClass.isInstance(element))
            {
                action.accept(expectedClass.cast(element));
            }
        }
    }

    public static int floorMod(int x, int other)
    {
        return Math.floorMod(x, other);
    }

    public static long floorMod(long x, long other)
    {
        return Math.floorMod(x, other);
    }

    /**
     * Set the value at position {@code index} in a {@link List} to {@code element}.  If the list has fewer
     * than {@code index} entries, extend the intermediate entries between its current size and
     * {@code index} with {@code fillerElement}.
     */
    public static <T> void setAndExtend(List<T> list, int index, T element, T fillerElement)
    {
        if (index >= list.size())
        {
            list.addAll(Collections.nCopies(index - list.size(), fillerElement));
            list.add(element);
        }
        else
        {
            list.set(index, element);
        }
    }

    /** We inline this so getStackTrace itself doesn't show up in the stack trace. */
    public static String getStackTrace()
    {
        StringBuffer sb = new StringBuffer();
        for (StackTraceElement ste : Thread.currentThread().getStackTrace())
        {
            sb.append(ste.toString()).append('\n');
        }
        return sb.toString();
    }

    public static ObjectNode appendAll(ObjectNode target, ObjectNode other)
    {
        Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = other.fields();
        while (fields.hasNext())
        {
            Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> entry = fields.next();
            target.set(entry.getKey(), entry.getValue());
        }
        return target;
    }
}
