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
package org.jitsi.videobridge.cc.av1;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket;
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc;
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure;
import org.jitsi.rtp.rtp.header_extensions.DTI;
import org.jitsi.rtp.rtp.header_extensions.FrameInfo;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * This class is responsible for dropping AV1 simulcast/svc packets based on
 * their quality, i.e. packets that correspond to qualities that are above a
 * given quality target. Instances of this class are thread-safe.
 */
class Av1DDQualityFilter
{
    /**
     * The default maximum frequency at which the media engine
     * generates key frame.
     */
    private static final Duration MIN_KEY_FRAME_WAIT = Duration.ofMillis(300);

    private final Map<Long, Av1DDFrameMap> av1FrameMap;

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
     * encoding or (in some cases) a decode target switch.
     */
    private boolean needsKeyframe = false;

    /**
     * The encoding ID that this instance tries to achieve. Upon
     * receipt of a packet, we check whether encoding in the externalTargetIndex
     * (that's specified as an argument to the
     * {@link #acceptFrame(Av1DDFrame, int, int, Instant)} method) is set to something different,
     * in which case we set {@link #needsKeyframe} equal to true and
     * update.
     */
    private int internalTargetEncoding = RtpLayerDesc.SUSPENDED_ENCODING_ID;

    /**
     * The decode target that this instance tries to achieve.
     */
    private int internalTargetDt = Av1DDRtpLayerDesc.SUSPENDED_DT;

    /**
     * The layer index that we're currently forwarding. {@link RtpLayerDesc#SUSPENDED_INDEX}
     * indicates that we're not forwarding anything. Reading/writing of this
     * field is synchronized on this instance.
     */
    private int currentIndex = RtpLayerDesc.SUSPENDED_INDEX;

    Av1DDQualityFilter(@NotNull Map<Long, Av1DDFrameMap> av1FrameMap, @NotNull Logger parentLogger)
    {
        this.av1FrameMap = av1FrameMap;
        this.logger = parentLogger.createChildLogger(Av1DDQualityFilter.class.getName());
    }

    boolean needsKeyframe()
    {
        return needsKeyframe;
    }

    /**
     * Determines whether to accept or drop an AV1 frame.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param frame the AV1 frame.
     * @param incomingEncoding The encoding ID of the incoming packet
     * @param externalTargetIndex the target quality index that the user of this
     * instance wants to achieve.
     * @param receivedTime the current time (as an Instant)
     * @return the result of the accept decision.
     */
    synchronized AcceptResult acceptFrame(
        @NotNull Av1DDFrame frame,
        int incomingEncoding,
        int externalTargetIndex,
        @Nullable Instant receivedTime)
    {
        int prevIndex = currentIndex;
        boolean accept = doAcceptFrame(frame, incomingEncoding, externalTargetIndex, receivedTime);
        int currentDt = Av1DDRtpLayerDesc.getDtFromIndex(currentIndex);
        FrameInfo frameInfo = frame.getFrameInfo();
        Av1TemplateDependencyStructure structure = frame.getStructure();
        boolean mark;
        if (currentDt != Av1DDRtpLayerDesc.SUSPENDED_DT && frameInfo != null && structure != null
            && currentDt >= 0 && currentDt < structure.getDecodeTargetLayers().size())
        {
            mark = frameInfo.getSpatialId() == structure.getDecodeTargetLayers().get(currentDt).getSpatialId();
        }
        else
        {
            mark = false;
        }
        boolean isResumption = prevIndex == RtpLayerDesc.SUSPENDED_INDEX && currentIndex != RtpLayerDesc.SUSPENDED_INDEX;
        if (isResumption)
        {
            // Every code path that can turn off SUSPENDED_INDEX also accepts
            if (!accept)
            {
                throw new IllegalStateException("isResumption=true but accept=false for frame "
                    + frame.getFrameNumber() + ", frameInfo=" + frame.getFrameInfo());
            }
        }
        boolean dtChanged = prevIndex != currentIndex;
        if (dtChanged && currentDt != Av1DDRtpLayerDesc.SUSPENDED_DT)
        {
            // Every code path that changes DT also accepts
            if (!accept)
            {
                throw new IllegalStateException("dtChanged=true but accept=false for frame "
                    + frame.getFrameNumber() + ", frameInfo=" + frame.getFrameInfo());
            }
        }
        Integer newDt = (dtChanged || frame.getActiveDecodeTargets() != null) ? currentDt : null;
        return new AcceptResult(accept, isResumption, mark, newDt);
    }

    private boolean doAcceptFrame(
        @NotNull Av1DDFrame frame,
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

        if (frame.isKeyframe())
        {
            logger.debug(() -> "Quality filter got keyframe for stream " + frame.getSsrc());
            return acceptKeyframe(frame, incomingEncoding, externalTargetIndex, receivedTime);
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

            /* Logic to forward a non-keyframe:
             * If the frame does not have FrameInfo, reject and set needsKeyframe (we couldn't decode its templates).
             * If we're trying to switch DTs in the current encoding, check the template structure to ensure that
             * there is at least one template which is SWITCH for the target DT and not NOT_PRESENT for the current
             * DT.
             * If there is not, request a keyframe.
             * If the current frame is SWITCH for the target DT, and we've forwarded all the frames on which (by
             * its fdiffs) it depends, forward it, and change the current DT to the target DT.
             * In normal circumstances (when the target index == the current index), or when we're trying to switch
             * *up* encodings, forward all frames whose DT for the current DT is not NOT_PRESENT.
             * If we're trying to switch *down* encodings, only forward frames which are REQUIRED or SWITCH for the
             * current DT.
             */
            FrameInfo frameInfo = frame.getFrameInfo();
            if (frameInfo == null)
            {
                needsKeyframe = true;
                return false;
            }
            int currentDt = Av1DDRtpLayerDesc.getDtFromIndex(currentIndex);
            int externalTargetDt = currentEncoding == externalTargetEncoding
                ? Av1DDRtpLayerDesc.getDtFromIndex(externalTargetIndex) : currentDt;

            if (frame.getActiveDecodeTargets() != null
                && !Av1DDPacket.containsDecodeTarget(frame.getActiveDecodeTargets(), externalTargetDt))
            {
                /* This shouldn't happen, because we should have set layeringChanged for this packet. */
                logger.warn("External target DT " + externalTargetDt + " not present in current decode targets 0x"
                    + Integer.toHexString(frame.getActiveDecodeTargets()) + " for frame " + frame + ".");
                return false;
            }

            if (currentDt != externalTargetDt)
            {
                Av1DDFrameMap frameMap = av1FrameMap.get(frame.getSsrc());
                List<DTI> dtiList = frameInfo.getDti();
                if (externalTargetDt < 0 || externalTargetDt >= dtiList.size())
                {
                    logger.warn("Target DT " + externalTargetDt + " not present for frame " + frame
                        + " [frameInfo " + frameInfo + "]");
                }
                boolean allDepsAccepted = true;
                if (frameMap != null)
                {
                    for (int fdiff : frameInfo.getFdiff())
                    {
                        Av1DDFrame dep = frameMap.getIndex(frame.getIndex() - fdiff);
                        if (dep == null || !dep.isAccepted())
                        {
                            allDepsAccepted = false;
                            break;
                        }
                    }
                }
                else
                {
                    allDepsAccepted = false;
                }

                DTI targetDti = (externalTargetDt >= 0 && externalTargetDt < dtiList.size())
                    ? dtiList.get(externalTargetDt) : null;

                if (targetDti == DTI.SWITCH && frameMap != null && allDepsAccepted)
                {
                    int finalCurrentDt = currentDt;
                    logger.debug(() -> "Switching to DT " + externalTargetDt + " from " + finalCurrentDt);
                    currentDt = externalTargetDt;
                    currentIndex = externalTargetIndex;
                }
                else
                {
                    Av1TemplateDependencyStructure structure = frame.getStructure();
                    if (structure == null || !structure.canSwitchWithoutKeyframe(currentDt, externalTargetDt))
                    {
                        int finalCurrentDt = currentDt;
                        logger.debug(() -> "Want to switch to DT " + externalTargetDt + " from " + finalCurrentDt
                            + ", requesting keyframe");
                        needsKeyframe = true;
                    }
                }
            }

            DTI currentFrameDti = (currentDt >= 0 && currentDt < frameInfo.getDti().size())
                ? frameInfo.getDti().get(currentDt) : DTI.NOT_PRESENT;
            if (currentEncoding > externalTargetEncoding)
            {
                return currentFrameDti == DTI.SWITCH || currentFrameDti == DTI.REQUIRED;
            }
            else
            {
                return currentFrameDti != DTI.NOT_PRESENT;
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
     * Determines whether to accept or drop an AV1 keyframe. This method updates
     * the encoding id.
     *
     * Note that, at the time of this writing, there's no practical need for a
     * synchronized keyword because there's only one thread accessing this
     * method at a time.
     *
     * @param receivedTime the time the frame was received
     * @return true to accept the AV1 keyframe, otherwise false.
     */
    private synchronized boolean acceptKeyframe(
        @NotNull Av1DDFrame frame, int incomingEncoding, int externalTargetIndex, @Nullable Instant receivedTime)
    {
        // This branch writes the {@link #currentSpatialLayerId} and it
        // determines whether or not we should switch to another simulcast
        // stream.
        if (incomingEncoding < 0)
        {
            // something went terribly wrong, normally we should be able to
            // extract the layer id from a keyframe.
            logger.error("unable to get layer id from keyframe");
            return false;
        }
        FrameInfo frameInfo = frame.getFrameInfo();
        if (frameInfo == null)
        {
            // something went terribly wrong, normally we should be able to
            // extract the frame info from a keyframe.
            logger.error("unable to get frame info from keyframe");
            return false;
        }
        logger.debug(() -> "Received a keyframe of encoding: " + incomingEncoding);

        int currentEncoding = RtpLayerDesc.getEidFromIndex(currentIndex);
        int externalTargetEncoding = RtpLayerDesc.getEidFromIndex(externalTargetIndex);

        int indexIfSwitched;
        if (incomingEncoding == externalTargetEncoding)
        {
            indexIfSwitched = externalTargetIndex;
        }
        else if (incomingEncoding == internalTargetEncoding && internalTargetDt != -1)
        {
            indexIfSwitched = Av1DDRtpLayerDesc.getIndex(currentEncoding, internalTargetDt);
        }
        else
        {
            List<Integer> dtisPresent = frameInfo.getDtisPresent();
            int max = Integer.MIN_VALUE;
            for (int dt : dtisPresent)
            {
                if (dt > max)
                {
                    max = dt;
                }
            }
            if (dtisPresent.isEmpty())
            {
                throw new IllegalStateException("No decode targets present in frame info " + frameInfo);
            }
            indexIfSwitched = max;
        }
        int dtIfSwitched = Av1DDRtpLayerDesc.getDtFromIndex(indexIfSwitched);
        List<DTI> dtiList = frameInfo.getDti();
        DTI dtiIfSwitched = (dtIfSwitched >= 0 && dtIfSwitched < dtiList.size())
            ? dtiList.get(dtIfSwitched) : DTI.NOT_PRESENT;
        boolean acceptIfSwitched = dtiIfSwitched != DTI.NOT_PRESENT;

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
                // If the target is 180p and the first keyframe of a group of
                // keyframes is a 720p keyframe we don't project it. If we
                // receive a 720p keyframe, we know that there MUST be a 180p
                // keyframe shortly after.
                if (acceptIfSwitched)
                {
                    currentIndex = indexIfSwitched;
                }
                return acceptIfSwitched;
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
            if (currentEncoding <= incomingEncoding && incomingEncoding <= internalTargetEncoding)
            {
                // upscale or current quality case
                if (acceptIfSwitched)
                {
                    currentIndex = indexIfSwitched;
                    logger.debug(() -> "Upscaling to encoding " + incomingEncoding + ". "
                        + "The target is " + internalTargetEncoding);
                }
                return acceptIfSwitched;
            }
            else if (incomingEncoding <= internalTargetEncoding && internalTargetEncoding < currentEncoding)
            {
                // downscale case
                if (acceptIfSwitched)
                {
                    currentIndex = indexIfSwitched;
                    logger.debug(() -> "Downscaling to encoding " + incomingEncoding + ". "
                        + "The target is " + internalTargetEncoding);
                }
                return acceptIfSwitched;
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
        pt.addField("qf.currentIndex", Av1DDRtpLayerDesc.indexString(currentIndex))
            .addField("qf.internalTargetEncoding", internalTargetEncoding)
            .addField("qf.internalTargetDt", internalTargetDt)
            .addField("qf.needsKeyframe", needsKeyframe)
            .addField(
                "qf.mostRecentKeyframeGroupArrivalTimeMs",
                mostRecentKeyframeGroupArrivalTime != null ? mostRecentKeyframeGroupArrivalTime.toEpochMilli() : -1);
        /* TODO any other fields necessary */
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
        debugState.put("internalTargetDt", internalTargetDt);
        debugState.put("currentIndex", Av1DDRtpLayerDesc.indexString(currentIndex));
        return debugState;
    }

    /**
     * The result of calling {@link #acceptFrame(Av1DDFrame, int, int, Instant)}.
     */
    static class AcceptResult
    {
        private final boolean accept;
        private final boolean isResumption;
        private final boolean mark;
        private final Integer newDt;

        AcceptResult(boolean accept, boolean isResumption, boolean mark, @Nullable Integer newDt)
        {
            this.accept = accept;
            this.isResumption = isResumption;
            this.mark = mark;
            this.newDt = newDt;
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

        @Nullable
        Integer getNewDt()
        {
            return newDt;
        }
    }
}
