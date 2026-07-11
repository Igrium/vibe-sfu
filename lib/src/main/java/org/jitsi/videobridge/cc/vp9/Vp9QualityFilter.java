/*
 * Copyright @ 2019 - present 8x8, Inc
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
package org.jitsi.videobridge.cc.vp9;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;

import java.time.Duration;
import java.time.Instant;

/**
 * This class is responsible for dropping VP9 simulcast/svc packets based on
 * their quality, i.e. packets that correspond to qualities that are above a
 * given quality target. Instances of this class are thread-safe.
 */
class Vp9QualityFilter
{
    /**
     * The default maximum frequency at which the media engine
     * generates key frame.
     */
    private static final Duration MIN_KEY_FRAME_WAIT = Duration.ofMillis(300);

    /**
     * The maximum possible number of VP9 spatial layers.
     */
    private static final int MAX_VP9_LAYERS = 8;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Holds the arrival time of the most recent keyframe group.
     * Reading/writing of this field is synchronized on this instance.
     */
    private @Nullable Instant mostRecentKeyframeGroupArrivalTime = null;

    /**
     * A boolean flag that indicates whether a keyframe is needed, due to an
     * encoding or (in some cases) a spatial layer switch.
     */
    private boolean needsKeyframe = false;

    /**
     * The encoding ID that this instance tries to achieve. Upon
     * receipt of a packet, we check whether encoding in the externalTargetIndex
     * (that's specified as an argument to the
     * {@link #acceptFrame(Vp9Frame, int, int, Instant)} method) is set to something different,
     * in which case we set {@link #needsKeyframe} equal to true and
     * update.
     */
    private int internalTargetEncoding = RtpLayerDesc.SUSPENDED_ENCODING_ID;

    /**
     * The spatial layer that this instance tries to achieve.
     */
    private int internalTargetSpatialId = RtpLayerDesc.SUSPENDED_ENCODING_ID;

    /**
     * The layer index that we're currently forwarding. {@link RtpLayerDesc#SUSPENDED_INDEX}
     * indicates that we're not forwarding anything. Reading/writing of this
     * field is synchronized on this instance.
     */
    private int currentIndex = RtpLayerDesc.SUSPENDED_INDEX;

    /**
     * Which spatial layers are currently being forwarded.
     */
    private final boolean[] layers = new boolean[MAX_VP9_LAYERS];

    Vp9QualityFilter(@NotNull Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(Vp9QualityFilter.class.getName());
    }

    boolean needsKeyframe()
    {
        return needsKeyframe;
    }

    /**
     * Determines whether to accept or drop a VP9 frame.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param frame the VP9 frame.
     * @param incomingEncoding the encoding of the incoming RTP packet
     * @param externalTargetIndex the target quality index that the user of this
     * instance wants to achieve.
     * @param receivedTime the current time (as an Instant)
     * @return the result of the accept decision.
     */
    synchronized AcceptResult acceptFrame(
        @NotNull Vp9Frame frame,
        int incomingEncoding,
        int externalTargetIndex,
        @Nullable Instant receivedTime)
    {
        int prevIndex = currentIndex;
        boolean accept = doAcceptFrame(frame, incomingEncoding, externalTargetIndex, receivedTime);
        boolean mark;
        if (frame.isInterPicturePredicted())
        {
            mark = Math.max(frame.getSpatialLayer(), 0) == RtpLayerDesc.getSidFromIndex(currentIndex);
        }
        else
        {
            /* This is wrong if the stream isn't actually currently encoding the target index's spatial layer */
            /* However, in that case the final (lower) spatial layer should have the marker bit set by the encoder,
               so I think this shouldn't be a problem? */
            mark = Math.max(frame.getSpatialLayer(), 0) == RtpLayerDesc.getSidFromIndex(externalTargetIndex);
        }
        boolean isResumption = prevIndex == RtpLayerDesc.SUSPENDED_INDEX && currentIndex != RtpLayerDesc.SUSPENDED_INDEX;
        if (isResumption)
        {
            assert accept; // Every code path that can turn off SUSPENDED_INDEX also accepts
        }
        return new AcceptResult(accept, isResumption, mark);
    }

    private boolean doAcceptFrame(
        @NotNull Vp9Frame frame,
        int incomingEncoding,
        int externalTargetIndex,
        @Nullable Instant receivedTime)
    {
        int externalTargetEncoding = RtpLayerDesc.getEidFromIndex(externalTargetIndex);
        int currentEncoding = RtpLayerDesc.getEidFromIndex(currentIndex);

        if (externalTargetEncoding != internalTargetEncoding)
        {
            // The externalEncodingIdTarget has changed since accept last
            // ran; perhaps we should request a keyframe.
            internalTargetEncoding = externalTargetEncoding;
            if (externalTargetEncoding != RtpLayerDesc.SUSPENDED_ENCODING_ID
                && externalTargetEncoding != currentEncoding)
            {
                needsKeyframe = true;
            }
        }
        if (externalTargetEncoding == RtpLayerDesc.SUSPENDED_ENCODING_ID)
        {
            // We stop forwarding immediately. We will need a keyframe in order
            // to resume.
            currentIndex = RtpLayerDesc.SUSPENDED_INDEX;
            return false;
        }
        // If temporal scalability is not enabled, pretend that this is the base temporal layer.
        int temporalLayerIdOfFrame = Math.max(frame.getTemporalLayer(), 0);

        if (frame.isKeyframe())
        {
            logger.debug(() -> "Quality filter got keyframe for stream " + frame.getSsrc());
            boolean accept = acceptKeyframe(frame, incomingEncoding, receivedTime);
            if (accept)
            {
                // Keyframes reset layer forwarding, whether or not they're an encoding switch
                for (int i = 0; i < layers.length; i++)
                {
                    layers[i] = (i == 0);
                }
            }
            return accept;
        }
        else if (currentEncoding != RtpLayerDesc.SUSPENDED_ENCODING_ID)
        {
            if (isOutOfSwitchingPhase(receivedTime) && isPossibleToSwitch(incomingEncoding))
            {
                // XXX(george) i've noticed some "rogue" base layer keyframes
                // that trigger this. what happens is the client sends a base
                // layer key frame, the bridge switches to that layer because
                // for all it knows it may be the only keyframe sent by the
                // client engine. then the bridge notices that packets from the
                // higher quality streams are flowing and execution ends-up
                // here. it is a mystery why the engine is "leaking" base layer
                // key frames
                needsKeyframe = true;
            }
            if (incomingEncoding != currentEncoding)
            {
                // for non-keyframes, we can't route anything but the current encoding
                return false;
            }

            /* Logic to forward spatial layer:
             * Can forward layer: If layer is being sent, or frame is not inter-picture predicted; and
             *  if layer below is being sent, or if frame does not use inter-picture dependency.
             * Switching layers: If layer of frame is closer to target than current, set current to target
             *  if can forward, otherwise request keyframe.
             * Want to forward layer: If layer of frame is equal to currentLayer, or is less than currentLayer
             *  and isUpperLayerReference.
             * If wantToForward and !canForward, something's wrong, request keyFrame.
             * accept = wantToForward && canForward
             * if (tid == 0)
             *   layers[layerOfFrame] = accept
             * return accept
             */

            int spatialLayerOfFrame = Math.max(frame.getSpatialLayer(), 0);
            int externalTargetSpatialId = RtpLayerDesc.getSidFromIndex(externalTargetIndex);
            int currentSpatialLayer = RtpLayerDesc.getSidFromIndex(currentIndex);

            /* If the stream includes a new SS which doesn't list the target spatial layer, lower the target. */
            /* The target should change the next time the BitrateController runs, but that may be some time. */
            if (frame.getNumSpatialLayers() != -1 && externalTargetSpatialId >= frame.getNumSpatialLayers())
            {
                externalTargetSpatialId = frame.getNumSpatialLayers() - 1;
            }

            boolean canForwardLayer = (!frame.isInterPicturePredicted() || layers[spatialLayerOfFrame])
                && (!frame.usesInterLayerDependency() || layers[spatialLayerOfFrame - 1]);

            /* TODO: this logic is fragile in the presence of frame reordering. */
            boolean wantToSwitch =
                (spatialLayerOfFrame > currentSpatialLayer && spatialLayerOfFrame <= externalTargetSpatialId)
                    || (spatialLayerOfFrame < currentSpatialLayer && spatialLayerOfFrame >= externalTargetSpatialId)
                    || (frame.getNumSpatialLayers() != -1 && currentSpatialLayer >= frame.getNumSpatialLayers());

            if (wantToSwitch)
            {
                if (canForwardLayer)
                {
                    int finalExternalTargetSpatialId = externalTargetSpatialId;
                    int finalCurrentSpatialLayer = currentSpatialLayer;
                    logger.debug(() -> "Switching to spatial layer " + finalExternalTargetSpatialId
                        + " from " + finalCurrentSpatialLayer);
                    currentIndex = RtpLayerDesc.getIndex(incomingEncoding, frame.getSpatialLayer(),
                        frame.getTemporalLayer());
                    currentSpatialLayer = spatialLayerOfFrame;
                }
                else
                {
                    if (internalTargetSpatialId != externalTargetSpatialId)
                    {
                        int finalExternalTargetSpatialId = externalTargetSpatialId;
                        int finalCurrentSpatialLayer = currentSpatialLayer;
                        logger.debug(() -> "Want to switch to spatial layer " + finalExternalTargetSpatialId
                            + " from " + finalCurrentSpatialLayer + ", requesting keyframe");
                    }
                    needsKeyframe = true;
                }
                internalTargetSpatialId = externalTargetSpatialId;
            }

            boolean wantToForwardLayer =
                (spatialLayerOfFrame == currentSpatialLayer)
                    || (spatialLayerOfFrame < currentSpatialLayer && frame.isUpperLevelReference());

            if (wantToForwardLayer && !canForwardLayer)
            {
                StringBuilder layersStr = new StringBuilder();
                for (int i = 0; i < layers.length; i++)
                {
                    if (i > 0)
                    {
                        layersStr.append(", ");
                    }
                    layersStr.append(layers[i]);
                }
                logger.warn("Want to forward " + RtpLayerDesc.indexString(currentIndex) + " frame, but can't! "
                    + "layers=" + layersStr + ", currentIndex=" + RtpLayerDesc.indexString(currentIndex) + ", "
                    + "isInterPicturePredicted=" + frame.isInterPicturePredicted() + ", "
                    + "usesInterLayerDependency=" + frame.usesInterLayerDependency() + ".");
            }

            boolean accept = wantToForwardLayer && canForwardLayer;

            if (temporalLayerIdOfFrame == 0)
            {
                layers[spatialLayerOfFrame] = accept;
            }

            if (!accept)
            {
                return false;
            }

            // This branch reads the {@link #currentEncodingId} and it
            // filters packets based on their temporal layer.
            /* TODO: pay attention to isSwitchingUpPoint.  (I believe the current VP9 encoders we deal with always
             *  have it set, however.) */
            if (currentEncoding > externalTargetEncoding || currentSpatialLayer > externalTargetSpatialId)
            {
                // pending downscale, decrease the frame rate until we
                // downscale.
                return temporalLayerIdOfFrame < 1;
            }
            else if (currentEncoding < externalTargetEncoding || currentSpatialLayer < externalTargetSpatialId)
            {
                // pending upscale, increase the frame rate until we upscale.
                return true;
            }
            else
            {
                // The currentEncoding exactly matches the externalTargetEncoding.
                int externalTargetTemporalId = RtpLayerDesc.getTidFromIndex(externalTargetIndex);
                int currentTemporalLayer = RtpLayerDesc.getTidFromIndex(currentIndex);

                boolean acceptTemporal = temporalLayerIdOfFrame <= externalTargetTemporalId;
                if (acceptTemporal && temporalLayerIdOfFrame > currentTemporalLayer)
                {
                    currentIndex = RtpLayerDesc.getIndex(currentEncoding, currentSpatialLayer, temporalLayerIdOfFrame);
                }
                return acceptTemporal;
            }
        }
        else
        {
            // In this branch we're not processing a keyframe and the
            // currentEncoding is in suspended state, which means we need
            // a keyframe to start streaming again.

            // We should have already requested a keyframe, either above or when the
            // internal target encoding was first moved off SUSPENDED_ENCODING.

            return false;
        }
    }

    /**
     * Returns a boolean that indicates whether we are in layer switching phase
     * or not.
     *
     * @param receivedTime the time the latest frame was received
     * @return false if we're in layer switching phase, true otherwise.
     */
    private synchronized boolean isOutOfSwitchingPhase(@Nullable Instant receivedTime)
    {
        if (receivedTime == null)
        {
            return false;
        }
        if (mostRecentKeyframeGroupArrivalTime == null)
        {
            return true;
        }
        Duration delta = Duration.between(mostRecentKeyframeGroupArrivalTime, receivedTime);
        return delta.compareTo(MIN_KEY_FRAME_WAIT) > 0;
    }

    /**
     * @return true if it looks like we can re-scale (see implementation of
     * method for specific details).
     */
    private synchronized boolean isPossibleToSwitch(int incomingEncoding)
    {
        int currentEncoding = RtpLayerDesc.getEidFromIndex(currentIndex);

        if (incomingEncoding == RtpLayerDesc.SUSPENDED_ENCODING_ID)
        {
            // We failed to resolve the spatial/quality layer of the packet.
            return false;
        }
        if (incomingEncoding > currentEncoding && currentEncoding < internalTargetEncoding)
        {
            // It looks like upscaling is possible
            return true;
        }
        else if (incomingEncoding < currentEncoding && currentEncoding > internalTargetEncoding)
        {
            // It looks like downscaling is possible.
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Determines whether to accept or drop a VP9 keyframe. This method updates
     * the encoding id.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param receivedTime the time the frame was received
     * @return true to accept the VP9 keyframe, otherwise false.
     */
    private synchronized boolean acceptKeyframe(
        @NotNull Vp9Frame frame, int incomingEncoding, @Nullable Instant receivedTime)
    {
        // This branch writes the {@link #currentSpatialLayerId} and it
        // determines whether or not we should switch to another simulcast
        // stream.
        if (incomingEncoding < 0)
        {
            // something went terribly wrong, normally we should be able to
            // extract the layer id from a keyframe.
            logger.error("invalid encoding id for keyframe");
            return false;
        }
        // Keyframes have to be sid 0, tid 0, unless something screwy is going on.
        // The layers can also be -1 if the layers aren't known
        if (frame.getSpatialLayer() > 0 || frame.getTemporalLayer() > 0)
        {
            logger.warn("Surprising layers S" + frame.getSpatialLayer() + "T" + frame.getTemporalLayer()
                + " on keyframe");
        }
        logger.debug(() -> "Received a keyframe of encoding: " + incomingEncoding);

        int incomingIndex = RtpLayerDesc.getIndex(incomingEncoding, frame.getSpatialLayer(), frame.getTemporalLayer());

        // The keyframe request has been fulfilled at this point, regardless of
        // whether we'll be able to achieve the internalEncodingIdTarget.
        needsKeyframe = false;
        if (isOutOfSwitchingPhase(receivedTime))
        {
            // During the switching phase we always project the first
            // keyframe because it may very well be the only one that we
            // receive (i.e. the endpoint is sending low quality only). Then
            // we try to approach the target.
            mostRecentKeyframeGroupArrivalTime = receivedTime;
            logger.debug(() -> "First keyframe in this kf group "
                + "currentEncodingId: " + incomingEncoding + ". "
                + "Target is " + internalTargetEncoding);
            if (incomingEncoding <= internalTargetEncoding)
            {
                int currentEncoding = RtpLayerDesc.getEidFromIndex(currentIndex);
                // If the target is 180p and the first keyframe of a group of
                // keyframes is a 720p keyframe we don't project it. If we
                // receive a 720p keyframe, we know that there MUST be a 180p
                // keyframe shortly after.
                if (currentEncoding != incomingEncoding)
                {
                    currentIndex = incomingIndex;
                }
                return true;
            }
            else
            {
                return false;
            }
        }
        else
        {
            // We're within the 300ms window since the reception of the
            // first key frame of a key frame group, let's check whether an
            // upscale/downscale is possible.
            int currentEncoding = RtpLayerDesc.getEidFromIndex(currentIndex);
            if (currentEncoding <= incomingEncoding && incomingEncoding <= internalTargetEncoding)
            {
                // upscale or current quality case
                if (currentEncoding != incomingEncoding)
                {
                    currentIndex = incomingIndex;
                }
                logger.debug(() -> "Upscaling to encoding " + incomingEncoding + ". "
                    + "The target is " + internalTargetEncoding);
                return true;
            }
            else if (incomingEncoding <= internalTargetEncoding && internalTargetEncoding < currentEncoding)
            {
                // downscale case
                currentIndex = incomingIndex;
                logger.debug(() -> "Downscaling to encoding " + incomingEncoding + ". "
                    + "The target is " + internalTargetEncoding);
                return true;
            }
            else
            {
                return false;
            }
        }
    }

    /**
     * Adds internal state to a diagnostic context time series point.
     */
    @SuppressFBWarnings(
        value = "IS2_INCONSISTENT_SYNC",
        justification = "We intentionally avoid synchronizing while reading fields only used in debug output.")
    void addDiagnosticContext(DiagnosticContext.TimeSeriesPoint pt)
    {
        pt.addField("qf.currentIndex", RtpLayerDesc.indexString(currentIndex))
            .addField("qf.internalTargetEncoding", internalTargetEncoding)
            .addField("qf.internalTargetSpatialId", internalTargetSpatialId)
            .addField("qf.needsKeyframe", needsKeyframe)
            .addField(
                "qf.mostRecentKeyframeGroupArrivalTimeMs",
                mostRecentKeyframeGroupArrivalTime != null ? mostRecentKeyframeGroupArrivalTime.toEpochMilli() : -1);
        for (int i = 0; i < layers.length; i++)
        {
            pt.addField("qf.layer." + i, layers[i]);
        }
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressFBWarnings(
        value = "IS2_INCONSISTENT_SYNC",
        justification = "We intentionally avoid synchronizing while reading fields only used in debug output.")
    ObjectNode getDebugState()
    {
        ObjectNode debugState = JsonNodeFactory.instance.objectNode();
        debugState.put(
            "mostRecentKeyframeGroupArrivalTimeMs",
            mostRecentKeyframeGroupArrivalTime != null ? mostRecentKeyframeGroupArrivalTime.toEpochMilli() : -1L);
        debugState.put("needsKeyframe", needsKeyframe);
        debugState.put("internalTargetEncoding", internalTargetEncoding);
        debugState.put("internalTargetSpatialId", internalTargetSpatialId);
        debugState.put("currentIndex", RtpLayerDesc.indexString(currentIndex));
        StringBuilder layersForwarded = new StringBuilder();
        for (boolean b : layers)
        {
            layersForwarded.append(Boolean.toString(b).charAt(0));
        }
        debugState.put("layersForwarded", layersForwarded.toString());
        return debugState;
    }

    /**
     * The result of calling {@link #acceptFrame(Vp9Frame, int, int, Instant)}.
     */
    static class AcceptResult
    {
        private final boolean accept;
        private final boolean isResumption;
        private final boolean mark;

        AcceptResult(boolean accept, boolean isResumption, boolean mark)
        {
            this.accept = accept;
            this.isResumption = isResumption;
            this.mark = mark;
        }

        boolean isAccept()
        {
            return accept;
        }

        boolean isResumption()
        {
            return isResumption;
        }

        boolean isMark()
        {
            return mark;
        }
    }
}
