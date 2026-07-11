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

package org.jitsi.videobridge.cc.allocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A Map of Endpoint IDs to their receiver video constraints.  Tracks the max height of all
 * of those constraints as new constraints are added and removed.
 */
public class ReceiverConstraintsMap
{
    private final Map<String, VideoConstraints> map = new HashMap<>();
    private final Object lock = new Object();
    private int maxHeight = 0;

    public int getMaxHeight()
    {
        return maxHeight;
    }

    public VideoConstraints put(String key, VideoConstraints value)
    {
        synchronized (lock)
        {
            VideoConstraints removed = map.put(key, value);
            if (value.getMaxHeight() == -1)
            {
                maxHeight = value.getMaxHeight();
            }
            else if (maxHeight != -1 && value.getMaxHeight() >= maxHeight)
            {
                maxHeight = value.getMaxHeight();
            }
            else if ((maxHeight == -1 || value.getMaxHeight() < maxHeight) &&
                removed != null && removed.getMaxHeight() == maxHeight)
            {
                maxHeight = findNextMax(maxHeight);
            }
            return removed;
        }
    }

    public VideoConstraints remove(String key)
    {
        synchronized (lock)
        {
            VideoConstraints removed = map.remove(key);
            if (removed != null && removed.getMaxHeight() == maxHeight)
            {
                maxHeight = findNextMax(maxHeight);
            }
            return removed;
        }
    }

    /**
     * Given the previous max, go through the constraints until we either:
     * 1) Find a maxHeight equal to the old one or
     * 2) Go through the whole list, and track the highest value we've seen.
     * Note: this method must be called with the lock held
     */
    private int findNextMax(int oldMaxHeight)
    {
        int maxSeen = 0;
        for (VideoConstraints constraints : map.values())
        {
            if (constraints.getMaxHeight() == oldMaxHeight)
            {
                return oldMaxHeight;
            }
            else if (maxSeen >= 0 && (constraints.getMaxHeight() > maxSeen || constraints.getMaxHeight() == -1))
            {
                maxSeen = constraints.getMaxHeight();
            }
        }
        return maxSeen;
    }

    public ObjectNode getDebugState()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("maxHeight", maxHeight);
        Map<String, String> stringified = map.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        node.set("constraints", new ObjectMapper().valueToTree(stringified));
        return node;
    }

    @Override
    public String toString()
    {
        return getDebugState().toString();
    }
}
