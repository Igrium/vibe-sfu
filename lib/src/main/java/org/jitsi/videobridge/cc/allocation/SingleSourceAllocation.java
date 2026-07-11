/*
 * Copyright @ 2021 - present 8x8, Inc.
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

import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.VideoType;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;
import org.jitsi.videobridge.cc.config.BitrateControllerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A bitrate allocation that pertains to a specific source. This is the internal representation used in the
 * allocation algorithm, as opposed to {@link BandwidthAllocation.SingleAllocation} which is the end result.
 *
 * @author George Politis
 * @author Pawel Domas
 */
class SingleSourceAllocation
{
    private static final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(
        BandwidthAllocator.class);

    private final String endpointId;
    private final MediaSourceDesc mediaSource;

    /** The constraints to use while allocating bandwidth to this media source. */
    private final VideoConstraints constraints;

    /** Whether the source is on stage. */
    private final boolean onStage;

    final Logger logger;

    /**
     * The immutable list of layers to be considered when allocating bandwidth.
     */
    private final Layers layers;

    /**
     * The index (into {@link #layers}) of the current target layer). It can be improved in the {@link #improve}
     * step, if there is enough bandwidth.
     */
    private int targetIdx = -1;

    SingleSourceAllocation(
        String endpointId,
        MediaSourceDesc mediaSource,
        VideoConstraints constraints,
        boolean onStage,
        DiagnosticContext diagnosticContext,
        java.time.Clock clock,
        Logger parentLogger)
    {
        this.endpointId = endpointId;
        this.mediaSource = mediaSource;
        this.constraints = constraints;
        this.onStage = onStage;
        this.logger = parentLogger.createChildLogger(SingleSourceAllocation.class.getName());
        this.logger.addContext(Map.of("remote_endpoint_id", endpointId));

        this.layers = selectLayers(mediaSource, onStage, constraints, clock.instant().toEpochMilli());

        if (timeSeriesLogger.isTraceEnabled())
        {
            DiagnosticContext.TimeSeriesPoint ratesTimeSeriesPoint = diagnosticContext
                .makeTimeSeriesPoint("layers_considered")
                .addField("remote_endpoint_id", endpointId);
            for (Layers.LayerSnapshot snapshot : layers)
            {
                RtpLayerDesc l = snapshot.getLayer();
                ratesTimeSeriesPoint.addField(
                    l.indexString() + "_" + l.getHeight() + "p_" + l.getFrameRate() + "fps_bps",
                    snapshot.getBitrate()
                );
            }
            timeSeriesLogger.trace(ratesTimeSeriesPoint);
        }
    }

    SingleSourceAllocation(
        String endpointId,
        MediaSourceDesc mediaSource,
        VideoConstraints constraints,
        boolean onStage,
        DiagnosticContext diagnosticContext,
        java.time.Clock clock)
    {
        this(
            endpointId,
            mediaSource,
            constraints,
            onStage,
            diagnosticContext,
            clock,
            new LoggerImpl(SingleSourceAllocation.class.getName())
        );
    }

    String getEndpointId()
    {
        return endpointId;
    }

    MediaSourceDesc getMediaSource()
    {
        return mediaSource;
    }

    VideoConstraints getConstraints()
    {
        return constraints;
    }

    boolean isOnStage()
    {
        return onStage;
    }

    boolean hasReachedPreferred()
    {
        return targetIdx >= layers.getPreferredIndex();
    }

    /**
     * Implements an "improve" step, incrementing {@link #targetIdx} to the next layer if there is sufficient
     * bandwidth. Note that this works eagerly up until the "preferred" layer (if any), and as a single step from
     * then on.
     *
     * @param remainingBps the additional bandwidth which is available on top of the bitrate of the current target
     * layer.
     * @return the bandwidth "consumed" by the method, i.e. the difference between the resulting and initial target
     * bitrate. E.g. if the target bitrate goes from 100 to 300 as a result if the method call, it will return 200.
     */
    long improve(long remainingBps, boolean allowOversending)
    {
        long initialTargetBitrate = getTargetBitrate();
        long maxBps = remainingBps + initialTargetBitrate;
        if (layers.isEmpty())
        {
            return 0;
        }
        if (targetIdx == -1 && layers.getPreferredIndex() > -1 && onStage)
        {
            // Boost on stage participant to preferred, if there's enough bw.
            for (int i = 0; i < layers.size(); i++)
            {
                if (i > layers.getPreferredIndex() || maxBps < layers.get(i).getBitrate())
                {
                    break;
                }
                targetIdx = i;
            }
        }
        else
        {
            // Try the next element in the ratedIndices array.
            if (targetIdx + 1 < layers.size() && layers.get(targetIdx + 1).getBitrate() < maxBps)
            {
                targetIdx++;
            }
        }
        if (targetIdx > -1)
        {
            // If there's a higher layer available with a lower bitrate, skip to it.
            //
            // For example, if 1080p@15fps is configured as a better subjective quality than 720p@30fps (i.e. it
            // sits on a higher index in the ratedIndices array) and the bitrate that we measure for the 1080p
            // stream is less than the bitrate that we measure for the 720p stream, then we "jump over" the 720p
            // stream and immediately select the 1080p stream.
            //
            // TODO further: Should we just prune the list of layers we consider to not include such layers?
            for (int i = layers.size() - 1; i >= targetIdx + 1; i--)
            {
                if (layers.get(i).getBitrate() <= layers.get(targetIdx).getBitrate())
                {
                    targetIdx = i;
                }
            }
        }

        // If oversending is allowed, look for a better layer which doesn't exceed maxBps by more than
        // `maxOversendBitrate`.
        if (allowOversending && layers.getOversendIndex() >= 0 && targetIdx < layers.getOversendIndex())
        {
            for (int i = layers.getOversendIndex(); i >= targetIdx + 1; i--)
            {
                if (layers.get(i).getBitrate() <= maxBps + BitrateControllerConfig.config.maxOversendBitrate.getBps())
                {
                    targetIdx = i;
                }
            }
        }

        long resultingTargetBitrate = getTargetBitrate();
        return resultingTargetBitrate - initialTargetBitrate;
    }

    /**
     * The source is suspended if we've not selected a layer AND the source has active layers.
     *
     * TODO: this is not exactly correct because it only looks at the layers we consider. E.g. if the receiver set
     * a maxHeight=0 constraint for an endpoint, it will appear suspended. This is not critical, because this is
     * only used for logging.
     */
    boolean isSuspended()
    {
        return targetIdx == -1 && !layers.isEmpty() && layers.get(0).getBitrate() > 0;
    }

    /**
     * Gets the target bitrate (in bps) for this endpoint allocation, i.e. the bitrate of the currently chosen
     * layer.
     */
    long getTargetBitrate()
    {
        Layers.LayerSnapshot targetLayer = getTargetLayerSnapshot();
        return targetLayer != null ? targetLayer.getBitrate() : 0;
    }

    private Layers.LayerSnapshot getTargetLayerSnapshot()
    {
        return (targetIdx >= 0 && targetIdx < layers.size()) ? layers.get(targetIdx) : null;
    }

    /**
     * Gets the ideal bitrate (in bps) for this endpoint allocation, i.e. the bitrate of the layer the bridge would
     * forward if there were no (bandwidth) constraints.
     */
    long getIdealBitrate()
    {
        Layers.LayerSnapshot idealLayer = layers.getIdealLayer();
        return idealLayer != null ? idealLayer.getBitrate() : 0;
    }

    /**
     * Exposed for testing only.
     */
    RtpLayerDesc getPreferredLayer()
    {
        Layers.LayerSnapshot preferred = layers.getPreferredLayer();
        return preferred != null ? preferred.getLayer() : null;
    }

    /**
     * Exposed for testing only.
     */
    RtpLayerDesc getOversendLayer()
    {
        Layers.LayerSnapshot oversend = layers.getOversendLayer();
        return oversend != null ? oversend.getLayer() : null;
    }

    /**
     * Creates the final immutable result of this allocation. Should be called once the allocation algorithm has
     * completed.
     */
    BandwidthAllocation.SingleAllocation getResult()
    {
        Layers.LayerSnapshot targetLayer = getTargetLayerSnapshot();
        Layers.LayerSnapshot idealLayer = layers.getIdealLayer();
        return new BandwidthAllocation.SingleAllocation(
            endpointId,
            mediaSource,
            targetLayer != null ? targetLayer.getLayer() : null,
            idealLayer != null ? idealLayer.getLayer() : null
        );
    }

    @Override
    public String toString()
    {
        return "[id=" + endpointId +
            " constraints=" + constraints +
            " ratedPreferredIdx=" + layers.getPreferredIndex() +
            " ratedTargetIdx=" + targetIdx;
    }

    /**
     * Selects from a list of layers the ones which should be considered when allocating bandwidth, as well as the
     * "preferred" and "oversend" layers. Logic specific to screensharing: we prioritize resolution over framerate,
     * prioritize the highest layer over other endpoints (by setting the highest layer as "preferred"), and allow
     * oversending up to the highest resolution (with low frame rate).
     */
    private Layers selectLayersForScreensharing(
        List<Layers.LayerSnapshot> layers,
        VideoConstraints constraints,
        boolean onStage)
    {
        List<Layers.LayerSnapshot> activeLayers =
            layers.stream().filter(l -> l.getBitrate() > 0).collect(Collectors.toList());
        // No active layers usually happens when the source has just been signaled and we haven't received
        // any packets yet. Add the layers here, so one gets selected and we can start forwarding sooner.
        if (activeLayers.isEmpty())
        {
            activeLayers = layers;
        }

        // We select all layers that satisfy the constraints.
        List<Layers.LayerSnapshot> selectedLayers;
        if (!constraints.heightIsLimited())
        {
            selectedLayers = activeLayers;
        }
        else
        {
            selectedLayers = activeLayers.stream()
                .filter(l -> l.getLayer().getHeight() <= constraints.getMaxHeight())
                .collect(Collectors.toList());
        }
        // If no layers satisfy the constraints, we use the layers with the lowest resolution.
        if (selectedLayers.isEmpty())
        {
            final List<Layers.LayerSnapshot> activeLayersFinal = activeLayers;
            Integer minHeight = activeLayersFinal.stream().map(l -> l.getLayer().getHeight()).min(Integer::compareTo)
                .orElse(null);
            if (minHeight == null)
            {
                return Layers.noLayers;
            }
            selectedLayers = activeLayersFinal.stream()
                .filter(l -> l.getLayer().getHeight() == minHeight)
                .collect(Collectors.toList());

            // This recognizes the structure used with VP9 (multiple encodings with the same resolution and
            // unknown frame rate). In this case, we only want the low quality layer. Unless we're on stage, in
            // which case we should consider all layers.
            if (!onStage && !selectedLayers.isEmpty() && selectedLayers.get(0).getLayer().getFrameRate() < 0)
            {
                selectedLayers = Collections.singletonList(selectedLayers.get(0));
            }
        }

        int oversendIdx;
        if (onStage && BitrateControllerConfig.config.allowOversendOnStage)
        {
            Integer maxHeight = selectedLayers.stream().map(l -> l.getLayer().getHeight()).max(Integer::compareTo)
                .orElse(null);
            if (maxHeight == null)
            {
                return Layers.noLayers;
            }
            // Of all layers with the highest resolution select the one with lowest bitrate. In case of VP9 the
            // layers are not necessarily ordered by bitrate.
            Layers.LayerSnapshot lowestBitrateLayer = selectedLayers.stream()
                .filter(l -> l.getLayer().getHeight() == maxHeight)
                .min((a, b) -> Long.compare(a.getBitrate(), b.getBitrate()))
                .orElse(null);
            if (lowestBitrateLayer == null)
            {
                return Layers.noLayers;
            }
            oversendIdx = selectedLayers.indexOf(lowestBitrateLayer);
        }
        else
        {
            oversendIdx = -1;
        }
        return new Layers(selectedLayers, selectedLayers.size() - 1, oversendIdx);
    }

    /**
     * Selects from the layers of a {@link MediaSourceDesc} the ones which should be considered when allocating
     * bandwidth for an endpoint. Also selects the indices of the "preferred" and "oversend" layers.
     *
     * @param source the endpoint which is the source of the stream(s).
     * @param constraints the constraints that the receiver specified for {@code source}.
     */
    private Layers selectLayers(
        MediaSourceDesc source,
        boolean onStage,
        VideoConstraints constraints,
        long nowMs)
    {
        if (constraints.getMaxHeight() == 0 || !source.hasRtpLayers())
        {
            return Layers.noLayers;
        }
        List<Layers.LayerSnapshot> layers = source.getRtpLayers().stream()
            .map(l -> new Layers.LayerSnapshot(
                l,
                BitrateControllerConfig.config.useVlaTargetBitrate
                    ? (l.getTargetBitrate() != null ? l.getTargetBitrate().getBps() : l.getBitrate(nowMs).getBps())
                    : l.getBitrate(nowMs).getBps()
            ))
            .collect(Collectors.toList());

        VideoType videoType = source.getVideoType();
        if (videoType == VideoType.CAMERA)
        {
            return selectLayersForCamera(layers, constraints);
        }
        else if (videoType == VideoType.DESKTOP || videoType == VideoType.DESKTOP_HIGH_FPS)
        {
            return selectLayersForScreensharing(layers, constraints, onStage);
        }
        else
        {
            return Layers.noLayers;
        }
    }

    /**
     * Selects from a list of layers the ones which should be considered when allocating bandwidth, as well as the
     * "preferred" and "oversend" layers. Logic specific to a camera stream: once the "preferred" height is reached
     * we require a high frame rate, with preconfigured values for the "preferred" height and frame rate, and we do
     * not allow oversending.
     */
    private Layers selectLayersForCamera(List<Layers.LayerSnapshot> layers, VideoConstraints constraints)
    {
        Integer minHeight = layers.stream().map(l -> l.getLayer().getHeight()).min(Integer::compareTo).orElse(null);
        if (minHeight == null)
        {
            return Layers.noLayers;
        }
        Double maxFps = layers.stream().map(l -> l.getLayer().getFrameRate()).max(Double::compareTo).orElse(null);
        if (maxFps == null)
        {
            return Layers.noLayers;
        }
        boolean noActiveLayers = layers.stream().noneMatch(l -> l.getBitrate() > 0);
        VideoConstraints preferred = getPreferred(constraints);
        int preferredHeight = preferred.getMaxHeight();
        double preferredFps = preferred.getMaxFrameRate();
        double effectivePreferredFps = maxFps > 0 ? Math.min(maxFps, preferredFps) : preferredFps;

        List<Layers.LayerSnapshot> ratesList = new ArrayList<>();
        // Initialize the list of layers to be considered. These are the layers that satisfy the constraints, with
        // a couple of exceptions (see comments below).
        for (Layers.LayerSnapshot layerSnapshot : layers)
        {
            RtpLayerDesc layer = layerSnapshot.getLayer();
            boolean lessThanPreferredHeight = layer.getHeight() < preferredHeight;
            boolean lessThanOrEqualMaxHeight =
                layer.getHeight() <= constraints.getMaxHeight() || !constraints.heightIsLimited();
            // If frame rate is unknown, consider it to be sufficient.
            boolean atLeastPreferredFps = layer.getFrameRate() < 0 || layer.getFrameRate() >= effectivePreferredFps;
            if (lessThanPreferredHeight ||
                (lessThanOrEqualMaxHeight && atLeastPreferredFps) ||
                layer.getHeight() == minHeight)
            {
                // No active layers usually happens when the source has just been signaled and we haven't received
                // any packets yet. Add the layers here, so one gets selected and we can start forwarding sooner.
                if (noActiveLayers || layerSnapshot.getBitrate() > 0)
                {
                    ratesList.add(layerSnapshot);
                }
            }
        }

        int effectivePreferredHeight = Math.max(preferredHeight, minHeight);
        int preferredIndex = lastIndexWhich(ratesList, l -> l.getLayer().getHeight() <= effectivePreferredHeight);
        final List<Layers.LayerSnapshot> ratesListFinal = ratesList;
        final int preferredIndexFinal = preferredIndex;
        logger.trace(() -> "Selected rates list " + ratesListFinal + ", preferred index " + preferredIndexFinal +
            " from layers " + layers + " with constraints " + constraints);

        return new Layers(ratesList, preferredIndex, -1);
    }

    /**
     * Returns the index of the last element of the list which satisfies the given predicate, or -1 if no elements
     * do.
     */
    private static int lastIndexWhich(
        List<Layers.LayerSnapshot> list,
        java.util.function.Predicate<Layers.LayerSnapshot> predicate)
    {
        int lastIndex = -1;
        for (int i = 0; i < list.size(); i++)
        {
            if (predicate.test(list.get(i)))
            {
                lastIndex = i;
            }
        }
        return lastIndex;
    }

    /**
     * Gets the "preferred" height and frame rate based on the constraints signaled from the receiver.
     *
     * For participants with sufficient maxHeight we favor frame rate over resolution. We consider all
     * temporal layers for resolutions lower than the preferred, but for resolutions >= preferred, we only
     * consider frame rates at least as high as the preferred. In practice this means we consider
     * 180p/7.5fps, 180p/15fps, 180p/30fps, 360p/30fps and 720p/30fps.
     */
    private static VideoConstraints getPreferred(VideoConstraints constraints)
    {
        if (constraints.getMaxHeight() > 180 || !constraints.heightIsLimited())
        {
            return new VideoConstraints(
                BitrateControllerConfig.config.onstagePreferredHeightPx,
                BitrateControllerConfig.config.onstagePreferredFramerate
            );
        }
        else
        {
            return VideoConstraints.UNLIMITED;
        }
    }
}
