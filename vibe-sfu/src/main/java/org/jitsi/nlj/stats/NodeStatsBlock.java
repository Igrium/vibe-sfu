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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.jitsi.nlj.util.StringBufferExtensions.appendLnIndent;

public class NodeStatsBlock
{
    /**
     * The stat name that we use to count the number of other blocks aggregated in this one.
     */
    private static final String AGGREGATES = "_aggregates";

    private final String name;

    private final Map<String, Object> stats = new LinkedHashMap<>();

    /**
     * Holds stats that are computed based on other values in the map (to e.g. calculate the
     * ratio of two values). Restricted to {@link Number} because this makes it easier to implement and
     * we don't need other values right now.
     */
    private final Map<String, Function<NodeStatsBlock, Number>> compoundStats = new LinkedHashMap<>();

    public NodeStatsBlock(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    /**
     * Adds a stat with a number value. Integral values are promoted to {@link Long}, while floating point values are
     * promoted to {@link Double}.
     */
    public void addNumber(String name, Number value)
    {
        Number promoted = promote(value);
        if (promoted != null)
        {
            stats.put(name, promoted);
        }
    }

    /**
     * Adds a stat with a string value.
     */
    public void addString(String name, String value)
    {
        stats.put(name, value);
    }

    /**
     * Adds a stat with a boolean value.
     */
    public void addBoolean(String name, boolean value)
    {
        stats.put(name, value);
    }

    /**
     * Adds another {@link NodeStatsBlock} as a child.
     */
    public void addBlock(NodeStatsBlock otherBlock)
    {
        stats.put(otherBlock.name, otherBlock);
    }

    /**
     * Adds a block with a given name from a JSON-ish key-value object map.
     */
    public void addJson(String name, Map<?, ?> json)
    {
        addBlock(fromJson(name, json));
    }

    public void addJson(String name, ObjectNode json)
    {
        addBlock(fromJson(name, json));
    }

    /**
     * Adds a named value to this {@link NodeStatsBlock} which is derived from other values in the block.
     * The value will be calculated (by invoking the given function) when it is needed (e.g. in {@link #getValue}
     * or when exporting this block to another format (printing or JSON).
     */
    public void addCompoundValue(String name, Function<NodeStatsBlock, Number> compoundValue)
    {
        compoundStats.put(name, compoundValue);
    }

    public Object getValue(String name)
    {
        if (stats.containsKey(name))
        {
            return stats.get(name);
        }
        if (compoundStats.containsKey(name))
        {
            return compoundStats.get(name).apply(this);
        }
        return null;
    }

    /**
     * Gets the value of a stat with a given name, if this {@link NodeStatsBlock} has it and it is a {@link Number}.
     * Otherwise returns {@code null}.
     */
    public Number getNumber(String name)
    {
        Object value = stats.get(name);
        if (value instanceof Number)
        {
            return (Number) value;
        }
        if (compoundStats.containsKey(name))
        {
            return compoundStats.get(name).apply(this);
        }
        return null;
    }

    public Number getNumberOrDefault(String name, Number defaultValue)
    {
        Number number = getNumber(name);
        return number != null ? number : defaultValue;
    }

    /**
     * Aggregates another block into this one. That is, takes any stats with number values from the
     * other block and updates the current block with the sum of the current and other value.
     */
    public void aggregate(NodeStatsBlock otherBlock)
    {
        otherBlock.stats.forEach((name, value) -> {
            Object existingValue = stats.get(name);
            if (existingValue == null && (value instanceof Long || value instanceof Double))
            {
                stats.put(name, value);
            }
            else if (existingValue instanceof Long && value instanceof Long)
            {
                stats.put(name, (Long) existingValue + (Long) value);
            }
            else if (existingValue instanceof Double && value instanceof Double)
            {
                stats.put(name, (Double) existingValue + (Double) value);
            }
            else if (existingValue instanceof Long && value instanceof Double)
            {
                stats.put(name, (Long) existingValue + (Double) value);
            }
            else if (existingValue instanceof Double && value instanceof Long)
            {
                stats.put(name, (Double) existingValue + (Long) value);
            }
            else
            {
                stats.put(name, value);
            }
        });
        otherBlock.compoundStats.forEach(this::addCompoundValue);
        Object aggregates = stats.getOrDefault(AGGREGATES, 0L);
        stats.put(AGGREGATES, ((Long) aggregates) + 1);
    }

    /**
     * Promotes integer values to {@link Long} and floating point values to {@link Double}. Returns a
     * {@link Long}, {@link Double}, or null.
     */
    private Number promote(Number n)
    {
        if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long)
        {
            return n.longValue();
        }
        if (n instanceof Float || n instanceof Double)
        {
            return n.doubleValue();
        }
        return null;
    }

    public String prettyPrint()
    {
        return prettyPrint(0);
    }

    public String prettyPrint(int indentLevel)
    {
        StringBuffer sb = new StringBuffer();
        appendLnIndent(sb, indentLevel, name);
        stats.forEach((statName, statValue) -> {
            if (statValue instanceof NodeStatsBlock)
            {
                sb.append(((NodeStatsBlock) statValue).prettyPrint(indentLevel + 2)).append('\n');
            }
            else
            {
                appendLnIndent(sb, indentLevel + 2, statName + ": " + statValue);
            }
        });
        compoundStats.forEach((statName, function) -> {
            Number statValue = function.apply(this);
            appendLnIndent(sb, indentLevel + 2, statName + ": " + statValue);
        });
        return sb.toString();
    }

    /**
     * Returns a JSON representation of this {@link NodeStatsBlock}.
     */
    public ObjectNode toJson()
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        stats.forEach((name, value) -> {
            if (value instanceof NodeStatsBlock)
            {
                o.set(name, ((NodeStatsBlock) value).toJson());
            }
            else if (value instanceof Long)
            {
                o.put(name, (Long) value);
            }
            else if (value instanceof Double)
            {
                o.put(name, (Double) value);
            }
            else if (value instanceof Boolean)
            {
                o.put(name, (Boolean) value);
            }
            else if (value instanceof String)
            {
                o.put(name, (String) value);
            }
            else
            {
                o.put(name, value.toString());
            }
        });
        compoundStats.forEach((name, function) -> {
            Number num = function.apply(this);
            if (num instanceof Long)
            {
                o.put(name, (Long) num);
            }
            else if (num instanceof Double)
            {
                o.put(name, (Double) num);
            }
            else
            {
                o.put(name, num.longValue());
            }
        });
        return o;
    }

    /**
     * Creates a {@link NodeStatsBlock} from a JSON-ish key-value object map. It recognizes recursively included
     * objects.
     */
    public static NodeStatsBlock fromJson(String name, Map<?, ?> json)
    {
        NodeStatsBlock block = new NodeStatsBlock(name);
        json.forEach((key, value) -> {
            if (value instanceof Map)
            {
                block.addBlock(fromJson(String.valueOf(key), (Map<?, ?>) value));
            }
            else if (value instanceof Number)
            {
                block.addNumber(String.valueOf(key), (Number) value);
            }
            else if (value instanceof Boolean)
            {
                block.addBoolean(String.valueOf(key), (Boolean) value);
            }
            else
            {
                block.addString(String.valueOf(key), String.valueOf(value));
            }
        });
        return block;
    }

    public static NodeStatsBlock fromJson(String name, ObjectNode json)
    {
        NodeStatsBlock block = new NodeStatsBlock(name);
        Map.Entry<String, JsonNode> entry;
        java.util.Iterator<Map.Entry<String, JsonNode>> fields = json.fields();
        while (fields.hasNext())
        {
            entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isObject())
            {
                block.addBlock(fromJson(key, (ObjectNode) value));
            }
            else if (value.isNumber())
            {
                block.addNumber(key, value.numberValue());
            }
            else if (value.isBoolean())
            {
                block.addBoolean(key, value.booleanValue());
            }
            else
            {
                block.addString(key, value.asText());
            }
        }
        return block;
    }
}
