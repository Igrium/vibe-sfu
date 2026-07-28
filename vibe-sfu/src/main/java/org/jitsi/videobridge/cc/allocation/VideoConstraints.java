/*
 * Copyright @ 2020 - present 8x8, Inc.
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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * (Deviation: upstream is a Kotlin {@code data class}; ported here as a plain immutable Java class with
 * {@code equals()}/{@code hashCode()} generated to match the data-class semantics used by callers, e.g.
 * {@link AllocationSettingsWrapper}'s {@code !=} comparisons.)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoConstraints
{
    public static final int UNLIMITED_HEIGHT = -1;
    public static final double UNLIMITED_FRAME_RATE = -1.0;
    public static final VideoConstraints NOTHING = new VideoConstraints(0);
    public static final VideoConstraints UNLIMITED = new VideoConstraints(UNLIMITED_HEIGHT, UNLIMITED_FRAME_RATE);

    private final int maxHeight;
    private final double maxFrameRate;

    public VideoConstraints(int maxHeight)
    {
        this(maxHeight, UNLIMITED_FRAME_RATE);
    }

    public VideoConstraints(int maxHeight, double maxFrameRate)
    {
        if (!(maxHeight == UNLIMITED_HEIGHT || maxHeight >= 0))
        {
            throw new IllegalArgumentException("maxHeight must be either -1, 0, or positive.");
        }
        if (!(maxFrameRate == UNLIMITED_FRAME_RATE || maxFrameRate >= 0.0))
        {
            throw new IllegalArgumentException("maxFrameRate must be either -1, or >= 0");
        }
        this.maxHeight = maxHeight;
        this.maxFrameRate = maxFrameRate;
    }

    public int getMaxHeight()
    {
        return maxHeight;
    }

    public double getMaxFrameRate()
    {
        return maxFrameRate;
    }

    @Override
    public String toString()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("maxHeight", maxHeight);
        node.put("maxFrameRate", maxFrameRate);
        return node.toString();
    }

    public boolean heightIsLimited()
    {
        return maxHeight != UNLIMITED_HEIGHT;
    }

    public boolean frameRateIsLimited()
    {
        return maxFrameRate != UNLIMITED_FRAME_RATE;
    }

    public boolean isDisabled()
    {
        return maxHeight == 0 || maxFrameRate == 0.0;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof VideoConstraints))
        {
            return false;
        }
        VideoConstraints that = (VideoConstraints) o;
        return maxHeight == that.maxHeight && Double.compare(that.maxFrameRate, maxFrameRate) == 0;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(maxHeight, maxFrameRate);
    }

    /**
     * (Ported from the top-level Kotlin extension function {@code Map<String, VideoConstraints>.prettyPrint()};
     * Java has no extension functions, so this becomes a static helper.)
     */
    public static String prettyPrint(Map<String, VideoConstraints> constraints)
    {
        return constraints.entrySet().stream()
            .map(e -> e.getKey() + "->" + e.getValue().getMaxHeight())
            .collect(Collectors.joining(", "));
    }
}
