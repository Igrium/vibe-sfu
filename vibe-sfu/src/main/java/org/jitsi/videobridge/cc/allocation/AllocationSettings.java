/*
 * Copyright @ 2020 - present 8x8, Inc.
 * Copyright @ 2021 - Vowel, Inc.
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
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi.videobridge.cc.config.BitrateControllerConfig;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This class encapsulates all of the client-controlled settings for bandwidth allocation.
 *
 * (Deviation: upstream is a Kotlin {@code data class} constructed with {@code @JvmOverloads}, which generates
 * multiple Java-visible constructors with defaulted trailing parameters. This ports the full constructor plus the
 * one convenience overload actually used elsewhere in the ported code.)
 */
public class AllocationSettings
{
    /**
     * (Deviation: upstream backs this with jitsi-metaconfig ({@code videobridge.cc.initial-last-n}, which this
     * library strips. Plain constant with the upstream {@code reference.conf} default instead.)
     */
    public static final int defaultInitialLastN = -1;

    /** @deprecated use {@link #onStageSources} */
    @Deprecated
    private final List<String> onStageEndpoints;

    /** @deprecated use {@link #selectedSources} */
    @Deprecated
    private final List<String> selectedEndpoints;

    private final List<String> onStageSources;
    private final List<String> selectedSources;
    private final Map<String, VideoConstraints> videoConstraints;
    private final int lastN;
    private final VideoConstraints defaultConstraints;

    /** A non-negative value is assumed as the available bandwidth in bps. A negative value is ignored. */
    private final long assumedBandwidthBps;

    public AllocationSettings(
        List<String> onStageEndpoints,
        List<String> selectedEndpoints,
        List<String> onStageSources,
        List<String> selectedSources,
        Map<String, VideoConstraints> videoConstraints,
        int lastN,
        VideoConstraints defaultConstraints,
        long assumedBandwidthBps)
    {
        this.onStageEndpoints = onStageEndpoints;
        this.selectedEndpoints = selectedEndpoints;
        this.onStageSources = onStageSources;
        this.selectedSources = selectedSources;
        this.videoConstraints = videoConstraints;
        this.lastN = lastN;
        this.defaultConstraints = defaultConstraints;
        this.assumedBandwidthBps = assumedBandwidthBps;
    }

    public AllocationSettings(VideoConstraints defaultConstraints)
    {
        this(
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyMap(),
            defaultInitialLastN,
            defaultConstraints,
            -1
        );
    }

    @Deprecated
    public List<String> getOnStageEndpoints()
    {
        return onStageEndpoints;
    }

    @Deprecated
    public List<String> getSelectedEndpoints()
    {
        return selectedEndpoints;
    }

    public List<String> getOnStageSources()
    {
        return onStageSources;
    }

    public List<String> getSelectedSources()
    {
        return selectedSources;
    }

    public Map<String, VideoConstraints> getVideoConstraints()
    {
        return videoConstraints;
    }

    public int getLastN()
    {
        return lastN;
    }

    public VideoConstraints getDefaultConstraints()
    {
        return defaultConstraints;
    }

    public long getAssumedBandwidthBps()
    {
        return assumedBandwidthBps;
    }

    public ObjectNode toJson()
    {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.set("on_stage_sources", mapper.valueToTree(onStageSources));
        node.set("selected_sources", mapper.valueToTree(selectedSources));
        Map<String, String> stringifiedConstraints = videoConstraints.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
        node.set("video_constraints", mapper.valueToTree(stringifiedConstraints));
        node.put("last_n", lastN);
        node.put("default_constraints", defaultConstraints.toString());
        node.put("assumed_bandwidth_bps", assumedBandwidthBps);
        return node;
    }

    @Override
    public String toString()
    {
        return toJson().toString();
    }

    public VideoConstraints getConstraints(String endpointId)
    {
        return videoConstraints.getOrDefault(endpointId, defaultConstraints);
    }

    /**
     * Maintains an {@link AllocationSettings} instance and allows fields to be set individually, with an
     * indication of whether the overall state changed.
     */
    public static class AllocationSettingsWrapper
    {
        private final Logger logger;

        /**
         * The last selected sources set signaled by the receiving endpoint.
         */
        private List<String> selectedSources = Collections.emptyList();

        int lastN = AllocationSettings.defaultInitialLastN;

        private Map<String, VideoConstraints> videoConstraints = Collections.emptyMap();

        private VideoConstraints defaultConstraints = new VideoConstraints(BitrateControllerConfig.config.initialMaxHeightPx);

        private long assumedBandwidthBps = -1;

        private List<String> onStageSources = Collections.emptyList();

        private AllocationSettings allocationSettings;

        private boolean everSet = false;

        private static final VideoConstraints defaultDefaultConstraints =
            new VideoConstraints(BitrateControllerConfig.config.defaultMaxHeightPx);

        public AllocationSettingsWrapper()
        {
            this(new LoggerImpl(AllocationSettingsWrapper.class.getName()));
        }

        public AllocationSettingsWrapper(Logger parentLogger)
        {
            this.logger = parentLogger.createChildLogger(AllocationSettingsWrapper.class.getName());
            this.allocationSettings = create();
        }

        private AllocationSettings create()
        {
            return new AllocationSettings(
                Collections.emptyList(),
                Collections.emptyList(),
                onStageSources,
                selectedSources,
                videoConstraints,
                lastN,
                defaultConstraints,
                assumedBandwidthBps
            );
        }

        public AllocationSettings get()
        {
            return allocationSettings;
        }

        public boolean setBandwidthAllocationSettings(ReceiverVideoConstraintsMessage message)
        {
            boolean changed = false;

            if (message.getLastN() != null && lastN != message.getLastN())
            {
                lastN = message.getLastN();
                changed = true;
            }
            if (message.getSelectedSources() != null && !selectedSources.equals(message.getSelectedSources()))
            {
                selectedSources = message.getSelectedSources();
                changed = true;
            }
            if (message.getOnStageSources() != null && !onStageSources.equals(message.getOnStageSources()))
            {
                onStageSources = message.getOnStageSources();
                changed = true;
            }

            VideoConstraints newDefaultConstraints = message.getDefaultConstraints() != null
                ? message.getDefaultConstraints()
                : (!everSet ? defaultDefaultConstraints : null);
            if (newDefaultConstraints != null && !newDefaultConstraints.equals(defaultConstraints))
            {
                defaultConstraints = newDefaultConstraints;
                changed = true;
            }

            if (message.getConstraints() != null && !videoConstraints.equals(message.getConstraints()))
            {
                videoConstraints = message.getConstraints();
                changed = true;
            }

            if (message.getAssumedBandwidthBps() != null)
            {
                Bandwidth limit = BitrateControllerConfig.config.assumedBandwidthLimit;
                long it = message.getAssumedBandwidthBps();
                if (limit != null)
                {
                    long limited = Math.min(it, limit.getBps());
                    if (assumedBandwidthBps != limited)
                    {
                        logger.warn(
                            "Setting assumed bandwidth " + Bandwidth.ofBps(limited) + " (receiver asked for " +
                                it + ")."
                        );
                        assumedBandwidthBps = limited;
                        changed = true;
                    }
                }
                else if (it >= 0)
                {
                    logger.info("Ignoring assumed-bandwidth-bps, not allowed in config.");
                }
            }

            if (changed)
            {
                allocationSettings = create();
            }
            everSet = true;
            return changed;
        }

        /**
         * Return {@code true} iff the {@link AllocationSettings} state changed.
         */
        public boolean setLastN(int lastN)
        {
            if (this.lastN != lastN)
            {
                this.lastN = lastN;
                allocationSettings = create();
                return true;
            }
            return false;
        }
    }

    /**
     * (Deviation: upstream {@code ReceiverVideoConstraintsMessage} is a {@code BridgeChannelMessage} subclass
     * with Jackson (de)serialization wired into the COLIBRI/bridge-channel signaling stack, which this library
     * strips. This is a plain data holder with the same fields; the host is responsible for constructing it from
     * whatever wire format it uses to receive receiver video constraints.)
     */
    public static class ReceiverVideoConstraintsMessage
    {
        private final Integer lastN;
        private final List<String> selectedSources;
        private final List<String> onStageSources;
        private final VideoConstraints defaultConstraints;
        private final Map<String, VideoConstraints> constraints;
        private final Long assumedBandwidthBps;

        public ReceiverVideoConstraintsMessage(
            Integer lastN,
            List<String> selectedSources,
            List<String> onStageSources,
            VideoConstraints defaultConstraints,
            Map<String, VideoConstraints> constraints,
            Long assumedBandwidthBps)
        {
            this.lastN = lastN;
            this.selectedSources = selectedSources;
            this.onStageSources = onStageSources;
            this.defaultConstraints = defaultConstraints;
            this.constraints = constraints;
            this.assumedBandwidthBps = assumedBandwidthBps;
        }

        public Integer getLastN()
        {
            return lastN;
        }

        public List<String> getSelectedSources()
        {
            return selectedSources;
        }

        public List<String> getOnStageSources()
        {
            return onStageSources;
        }

        public VideoConstraints getDefaultConstraints()
        {
            return defaultConstraints;
        }

        public Map<String, VideoConstraints> getConstraints()
        {
            return constraints;
        }

        public Long getAssumedBandwidthBps()
        {
            return assumedBandwidthBps;
        }
    }
}
