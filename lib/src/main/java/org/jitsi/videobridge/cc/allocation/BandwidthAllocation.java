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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.RtpLayerDesc;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The result of bandwidth allocation.
 */
public class BandwidthAllocation
{
    private final Set<SingleAllocation> allocations;
    private final boolean oversending;
    private final long idealBps;
    private final long targetBps;

    /** Whether any of the requested sources were suspended (no layer at all was selected) due to BWE. */
    private final List<String> suspendedSources;

    private final boolean hasSuspendedSources;
    private final Set<String> forwardedSources;

    public BandwidthAllocation(Set<SingleAllocation> allocations)
    {
        this(allocations, false, -1, -1, Collections.emptyList());
    }

    public BandwidthAllocation(
        Set<SingleAllocation> allocations,
        boolean oversending,
        long idealBps,
        long targetBps,
        List<String> suspendedSources)
    {
        this.allocations = allocations;
        this.oversending = oversending;
        this.idealBps = idealBps;
        this.targetBps = targetBps;
        this.suspendedSources = suspendedSources;
        this.hasSuspendedSources = !suspendedSources.isEmpty();
        this.forwardedSources = allocations.stream()
            .filter(SingleAllocation::isForwarded)
            .map(a -> a.getMediaSource() != null ? a.getMediaSource().getSourceName() : null)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    public Set<SingleAllocation> getAllocations()
    {
        return allocations;
    }

    public boolean isOversending()
    {
        return oversending;
    }

    public long getIdealBps()
    {
        return idealBps;
    }

    public long getTargetBps()
    {
        return targetBps;
    }

    public boolean getHasSuspendedSources()
    {
        return hasSuspendedSources;
    }

    public Set<String> getForwardedSources()
    {
        return forwardedSources;
    }

    /**
     * Whether the two allocations have the same endpoints and same layers.
     */
    public boolean isTheSameAs(BandwidthAllocation other)
    {
        if (allocations.size() != other.allocations.size() || oversending != other.oversending)
        {
            return false;
        }
        for (SingleAllocation allocation : allocations)
        {
            boolean matchFound = false;
            for (SingleAllocation otherAllocation : other.allocations)
            {
                if (Objects.equals(allocation.getEndpointId(), otherAllocation.getEndpointId()) &&
                    Objects.equals(
                        allocation.getMediaSource() != null ? allocation.getMediaSource().getPrimarySSRC() : null,
                        otherAllocation.getMediaSource() != null
                            ? otherAllocation.getMediaSource().getPrimarySSRC() : null
                    ) &&
                    Objects.equals(
                        allocation.getMediaSource() != null ? allocation.getMediaSource().getVideoType() : null,
                        otherAllocation.getMediaSource() != null
                            ? otherAllocation.getMediaSource().getVideoType() : null
                    ) &&
                    Objects.equals(
                        allocation.getTargetLayer() != null ? allocation.getTargetLayer().getIndex() : null,
                        otherAllocation.getTargetLayer() != null ? otherAllocation.getTargetLayer().getIndex() : null
                    ))
                {
                    matchFound = true;
                    break;
                }
            }
            if (!matchFound)
            {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString()
    {
        return "oversending=" + oversending + " " +
            allocations.stream().map(SingleAllocation::toString).collect(Collectors.joining(", "));
    }

    public ObjectNode getDebugState()
    {
        ObjectNode debugState = JsonNodeFactory.instance.objectNode();
        debugState.put("idealBps", idealBps);
        debugState.put("targetBps", targetBps);
        debugState.put("oversending", oversending);
        debugState.put("has_suspended_sources", hasSuspendedSources);
        debugState.put("suspended_sources", suspendedSources.toString());
        ObjectNode allocationsNode = JsonNodeFactory.instance.objectNode();
        for (SingleAllocation allocation : allocations)
        {
            String name = allocation.getMediaSource() != null
                ? allocation.getMediaSource().getSourceName() : allocation.getEndpointId();
            allocationsNode.set(name, allocation.getDebugState());
        }
        debugState.set("allocations", allocationsNode);
        return debugState;
    }

    /**
     * The result of bandwidth allocation for a specific endpoint and a {@link MediaSourceDesc}.
     *
     * (Deviation: upstream is a Kotlin {@code data class}; ported as a plain immutable holder.)
     */
    public static class SingleAllocation
    {
        /** The ID of the endpoint which owns the media source. */
        private final String endpointId;

        /** The media source. */
        private final MediaSourceDesc mediaSource;

        /** The layer which has been selected to be forwarded. */
        private final RtpLayerDesc targetLayer;

        /** The layer which would have been selected without bandwidth constraints. */
        private final RtpLayerDesc idealLayer;

        public SingleAllocation(String endpointId, MediaSourceDesc mediaSource)
        {
            this(endpointId, mediaSource, null, null);
        }

        public SingleAllocation(
            String endpointId,
            MediaSourceDesc mediaSource,
            RtpLayerDesc targetLayer,
            RtpLayerDesc idealLayer)
        {
            this.endpointId = endpointId;
            this.mediaSource = mediaSource;
            this.targetLayer = targetLayer;
            this.idealLayer = idealLayer;
        }

        public SingleAllocation(MediaSourceContainer endpoint)
        {
            this(endpoint, null, null);
        }

        public SingleAllocation(MediaSourceContainer endpoint, RtpLayerDesc targetLayer, RtpLayerDesc idealLayer)
        {
            this(
                endpoint.getId(),
                endpoint.getMediaSources().length > 0 ? endpoint.getMediaSources()[0] : null,
                targetLayer,
                idealLayer
            );
        }

        public String getEndpointId()
        {
            return endpointId;
        }

        public MediaSourceDesc getMediaSource()
        {
            return mediaSource;
        }

        public RtpLayerDesc getTargetLayer()
        {
            return targetLayer;
        }

        public RtpLayerDesc getIdealLayer()
        {
            return idealLayer;
        }

        private int getTargetIndex()
        {
            return targetLayer != null ? targetLayer.getIndex() : -1;
        }

        public boolean isForwarded()
        {
            return getTargetIndex() > -1;
        }

        @Override
        public String toString()
        {
            return "[epId=" + endpointId + " sourceName=" + (mediaSource != null ? mediaSource.getSourceName() : null) +
                " target=" + (targetLayer != null ? targetLayer.getHeight() : null) + "/" +
                (targetLayer != null ? targetLayer.getFrameRate() : null) +
                " (" + (targetLayer != null ? targetLayer.indexString() : null) + ") " +
                "ideal=" + (idealLayer != null ? idealLayer.getHeight() : null) + "/" +
                (idealLayer != null ? idealLayer.getFrameRate() : null) +
                " (" + (idealLayer != null ? idealLayer.indexString() : null) + ")]";
        }

        public ObjectNode getDebugState()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            if (targetLayer != null)
            {
                node.set("target", targetLayer.debugState());
            }
            else
            {
                node.putNull("target");
            }
            if (idealLayer != null)
            {
                node.set("ideal", idealLayer.debugState());
            }
            else
            {
                node.putNull("ideal");
            }
            return node;
        }
    }
}
