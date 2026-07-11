/*
 * Copyright @ 2019-present 8x8, Inc
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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket;
import org.jitsi.nlj.rtp.codec.av1.Av1DDRtpLayerDesc;
import org.jitsi.rtp.rtcp.RtcpSrPacket;
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure;
import org.jitsi.rtp.rtp.header_extensions.DTI;
import org.jitsi.rtp.rtp.header_extensions.FrameInfo;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.AdaptiveSourceProjectionContext;
import org.jitsi.videobridge.cc.RewriteException;
import org.jitsi.videobridge.cc.RtpState;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Av1DDAdaptiveSourceProjectionContext implements AdaptiveSourceProjectionContext
{
    /**
     * The time series logger for this class.
     */
    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(Av1DDAdaptiveSourceProjectionContext.class);

    private final DiagnosticContext diagnosticContext;

    private final Logger logger;

    /**
     * A map that stores the per-encoding AV1 frame maps.
     */
    private final Map<Long, Av1DDFrameMap> av1FrameMaps = new HashMap<>();

    /**
     * The {@link Av1DDQualityFilter} instance that does quality filtering on the
     * incoming pictures, to choose encodings and layers to forward.
     */
    private final Av1DDQualityFilter av1QualityFilter;

    private Av1DDFrameProjection lastAv1FrameProjection;

    /**
     * The frame number index that started the latest stream resumption.
     * We can't send frames with frame number less than this, because we don't have
     * space in the projected sequence number/frame number counts.
     */
    private long lastFrameNumberIndexResumption = -1L;

    public Av1DDAdaptiveSourceProjectionContext(
        @NotNull DiagnosticContext diagnosticContext,
        @NotNull RtpState rtpState,
        @Nullable Object persistentState,
        @NotNull Logger parentLogger)
    {
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(Av1DDAdaptiveSourceProjectionContext.class.getName());
        this.av1QualityFilter = new Av1DDQualityFilter(av1FrameMaps, logger);

        if (persistentState != null && !(persistentState instanceof Av1PersistentState))
        {
            throw new IllegalArgumentException(
                "persistentState must be null or an Av1PersistentState, was " + persistentState.getClass());
        }
        Av1PersistentState av1PersistentState = (Av1PersistentState) persistentState;

        this.lastAv1FrameProjection = new Av1DDFrameProjection(
            diagnosticContext,
            rtpState.ssrc,
            rtpState.maxSequenceNumber,
            rtpState.maxTimestamp,
            av1PersistentState != null ? av1PersistentState.getFrameNumber() : null,
            av1PersistentState != null ? av1PersistentState.getTemplateId() : null);
    }

    @Override
    public synchronized boolean accept(@NotNull PacketInfo packetInfo, int targetIndex)
    {
        if (!(packetInfo.getPacket() instanceof Av1DDPacket))
        {
            logger.warn("Packet is not AV1 DD Packet");
            return false;
        }
        Av1DDPacket packet = packetInfo.packetAs();
        int incomingEncoding = packet.getEncodingId();

        /* If insertPacketInMap returns null, this is a very old picture, more than Av1DDFrameMap.FRAME_MAP_SIZE
           old, or something is wrong with the stream. */
        Av1DDFrameMap.PacketInsertionResult result = insertPacketInMap(packet);
        if (result == null)
        {
            return false;
        }

        Av1DDFrame frame = result.getFrame();

        if (result.isNewFrame())
        {
            if (packet.isKeyframe() && frameIsNewSsrc(frame))
            {
                /* If we're not currently projecting this SSRC, check if we've
                 * already decided to drop a subsequent required frame of this SSRC for the DT.
                 * If we have, we can't turn on the encoding starting from this
                 * packet, so treat this frame as though it weren't a keyframe.
                 * Note that this may mean re-analyzing the packets with a now-available template dependency
                 * structure.
                 */
                if (haveSubsequentNonAcceptedChain(frame, incomingEncoding, targetIndex))
                {
                    frame.setKeyframe(false);
                }
            }
            Instant receivedTime = packetInfo.getReceivedTime();
            Av1DDQualityFilter.AcceptResult acceptResult =
                av1QualityFilter.acceptFrame(frame, incomingEncoding, targetIndex, receivedTime);
            frame.setAccepted(acceptResult.isAccept() && frameIsProjectable(frame));
            if (frame.isAccepted())
            {
                Av1DDFrameProjection projection;
                try
                {
                    projection = createProjection(
                        frame, packet, acceptResult.isMark(), acceptResult.isResumption(), result.isReset(),
                        acceptResult.getNewDt(), receivedTime);
                }
                catch (Exception e)
                {
                    logger.warn("Failed to create frame projection", e);
                    /* Make sure we don't have an accepted frame without a projection in the map. */
                    frame.setAccepted(false);
                    return false;
                }
                frame.setProjection(projection);
                if (RtpUtils.isNewerThan(
                    projection.getEarliestProjectedSeqNum(), lastAv1FrameProjection.getLatestProjectedSeqNum()))
                {
                    lastAv1FrameProjection = projection;
                }
            }
        }

        boolean accept;
        if (frame.isAccepted() && frame.getProjection() != null && frame.getProjection().accept(packet))
        {
            accept = true;
        }
        else
        {
            if (frame.isAccepted())
            {
                if (frame.getProjection() != null && frame.getProjection().getClosedSeq() != -1)
                {
                    logger.debug("Not accepting " + packet + ": frame projection is closed at "
                        + frame.getProjection().getClosedSeq());
                }
                else if (frame.getProjection() == null)
                {
                    logger.warn("Not accepting " + packet + ": frame has no projection, even though QF accepted it");
                }
                else
                {
                    logger.warn("Not accepting " + packet + ", even though frame projection is not closed");
                }
            }
            accept = false;
        }

        if (timeSeriesLogger.isTraceEnabled())
        {
            FrameInfo frameInfo = packet.getFrameInfo();
            DiagnosticContext.TimeSeriesPoint pt = diagnosticContext.makeTimeSeriesPoint("rtp_av1")
                .addField("ssrc", packet.getSsrc())
                .addField("timestamp", packet.getTimestamp())
                .addField("seq", packet.getSequenceNumber())
                .addField("frameNumber", packet.getFrameNumber())
                .addField("templateId", packet.getStatelessDescriptor().getFrameDependencyTemplateId())
                .addField("hasStructure",
                    packet.getDescriptor() != null && packet.getDescriptor().getNewTemplateDependencyStructure() != null)
                .addField("spatialLayer", frameInfo != null ? frameInfo.getSpatialId() : null)
                .addField("temporalLayer", frameInfo != null ? frameInfo.getTemporalId() : null)
                .addField("dti", frameInfo != null ? DTI.toShortString(frameInfo.getDti()) : null)
                .addField("hasInterPictureDependency", frameInfo != null ? frameInfo.hasInterPictureDependency() : null)
                // TODO add more relevant fields from AV1 DD for debugging
                .addField("targetIndex", Av1DDRtpLayerDesc.indexString(targetIndex))
                .addField("new_frame", result.isNewFrame())
                .addField("accept", accept)
                .addField("payload_length", packet.getPayloadLength())
                .addField("packet_length", packet.getLength());
            av1QualityFilter.addDiagnosticContext(pt);
            timeSeriesLogger.trace(pt);
        }

        return accept;
    }

    /** Look up an Av1DDFrame for a packet. */
    @Nullable
    private Av1DDFrame lookupAv1Frame(@NotNull Av1DDPacket av1Packet)
    {
        Av1DDFrameMap frameMap = av1FrameMaps.get(av1Packet.getSsrc());
        return frameMap == null ? null : frameMap.findFrame(av1Packet);
    }

    /**
     * Insert a packet in the appropriate {@link Av1DDFrameMap}.
     */
    @Nullable
    private Av1DDFrameMap.PacketInsertionResult insertPacketInMap(@NotNull Av1DDPacket av1Packet)
    {
        Av1DDFrameMap frameMap = av1FrameMaps.computeIfAbsent(av1Packet.getSsrc(), ssrc -> new Av1DDFrameMap(logger));
        return frameMap.insertPacket(av1Packet);
    }

    private boolean haveSubsequentNonAcceptedChain(@NotNull Av1DDFrame frame, int incomingEncoding, int targetIndex)
    {
        Av1DDFrameMap map = av1FrameMaps.get(frame.getSsrc());
        if (map == null)
        {
            return false;
        }
        Av1TemplateDependencyStructure structure = frame.getStructure();
        if (structure == null)
        {
            return false;
        }

        Set<Integer> dtsToCheck = new HashSet<>();
        if (incomingEncoding == RtpLayerDesc.getEidFromIndex(targetIndex))
        {
            dtsToCheck.add(Av1DDRtpLayerDesc.getDtFromIndex(targetIndex));
        }
        else
        {
            FrameInfo frameInfo = frame.getFrameInfo();
            if (frameInfo != null)
            {
                dtsToCheck.addAll(frameInfo.getDtisPresent());
            }
        }

        Set<Integer> chainsToCheck = new HashSet<>();
        List<Integer> decodeTargetProtectedBy = structure.getDecodeTargetProtectedBy();
        for (int dt : dtsToCheck)
        {
            if (dt >= 0 && dt < decodeTargetProtectedBy.size())
            {
                chainsToCheck.add(decodeTargetProtectedBy.get(dt));
            }
        }

        return map.nextFrameWith(frame, (Av1DDFrame f) ->
        {
            if (f.isAccepted())
            {
                return false;
            }
            if (f.getFrameInfo() == null)
            {
                f.updateParse(structure, logger);
            }
            for (int chainIdx : chainsToCheck)
            {
                if (partOfActiveChain(f, chainIdx))
                {
                    return true;
                }
            }
            return false;
        }) != null;
    }

    private static boolean partOfActiveChain(@NotNull Av1DDFrame frame, int chainIdx)
    {
        Av1TemplateDependencyStructure structure = frame.getStructure();
        if (structure == null)
        {
            return false;
        }
        FrameInfo frameInfo = frame.getFrameInfo();
        if (frameInfo == null)
        {
            return false;
        }
        List<Integer> decodeTargetProtectedBy = structure.getDecodeTargetProtectedBy();
        List<DTI> dti = frameInfo.getDti();
        for (int i = 0; i < decodeTargetProtectedBy.size(); i++)
        {
            if (decodeTargetProtectedBy.get(i) != chainIdx)
            {
                continue;
            }
            if (i >= dti.size() || dti.get(i) == DTI.NOT_PRESENT || dti.get(i) == DTI.DISCARDABLE)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Calculate the projected sequence number gap between two frames (of the same encoding),
     * allowing collapsing for unaccepted frames.
     */
    private int seqGap(@NotNull Av1DDFrame frame1, @NotNull Av1DDFrame frame2)
    {
        int seqGap = RtpUtils.getSequenceNumberDelta(
            frame2.getEarliestKnownSequenceNumber(), frame1.getLatestKnownSequenceNumber());

        if (!frame1.isAccepted() && !frame2.isAccepted() && frame2.isImmediatelyAfter(frame1))
        {
            /* If neither frame is being projected, and they have consecutive
               frame numbers, we don't need to leave any gap. */
            seqGap = 0;
        }
        else
        {
            /* If the earlier frame wasn't projected, and we haven't seen its
             * final packet, we know it has to consume at least one more sequence number. */
            if (!frame1.isAccepted() && !frame1.hasSeenEndOfFrame() && seqGap > 1)
            {
                seqGap--;
            }
            /* Similarly, if the later frame wasn't projected and we haven't seen
             * its first packet. */
            if (!frame2.isAccepted() && !frame2.hasSeenStartOfFrame() && seqGap > 1)
            {
                seqGap--;
            }
            /* If the frame wasn't accepted, it has to have consumed at least one sequence number,
             * which we can collapse out. */
            if (!frame1.isAccepted() && seqGap > 0)
            {
                seqGap--;
            }
        }
        return seqGap;
    }

    private boolean frameIsNewSsrc(@NotNull Av1DDFrame frame)
    {
        Av1DDFrame lastFrame = lastAv1FrameProjection.getAv1Frame();
        return lastFrame == null || !lastFrame.matchesSSRC(frame);
    }

    private boolean frameIsProjectable(@NotNull Av1DDFrame frame)
    {
        return frameIsNewSsrc(frame) || frame.getIndex() >= lastFrameNumberIndexResumption;
    }

    /**
     * Find the previous frame before the given one.
     */
    @Nullable
    private synchronized Av1DDFrame prevFrame(@NotNull Av1DDFrame frame)
    {
        Av1DDFrameMap frameMap = av1FrameMaps.get(frame.getSsrc());
        return frameMap == null ? null : frameMap.prevFrame(frame);
    }

    /**
     * Find the next frame after the given one.
     */
    @Nullable
    private synchronized Av1DDFrame nextFrame(@NotNull Av1DDFrame frame)
    {
        Av1DDFrameMap frameMap = av1FrameMaps.get(frame.getSsrc());
        return frameMap == null ? null : frameMap.nextFrame(frame);
    }

    /**
     * Find the previous accepted frame before the given one.
     */
    @Nullable
    private Av1DDFrame findPrevAcceptedFrame(@NotNull Av1DDFrame frame)
    {
        Av1DDFrameMap frameMap = av1FrameMaps.get(frame.getSsrc());
        return frameMap == null ? null : frameMap.findPrevAcceptedFrame(frame);
    }

    /**
     * Find the next accepted frame after the given one.
     */
    @Nullable
    private Av1DDFrame findNextAcceptedFrame(@NotNull Av1DDFrame frame)
    {
        Av1DDFrameMap frameMap = av1FrameMaps.get(frame.getSsrc());
        return frameMap == null ? null : frameMap.findNextAcceptedFrame(frame);
    }

    /**
     * Create a projection for this frame.
     */
    @NotNull
    private Av1DDFrameProjection createProjection(
        @NotNull Av1DDFrame frame,
        @NotNull Av1DDPacket initialPacket,
        boolean mark,
        boolean isResumption,
        boolean isReset,
        @Nullable Integer newDt,
        @Nullable Instant receivedTime)
    {
        if (frameIsNewSsrc(frame))
        {
            return createEncodingSwitchProjection(frame, initialPacket, mark, newDt, receivedTime);
        }
        else if (isReset)
        {
            return createResetProjection(frame, initialPacket, mark, newDt, receivedTime);
        }
        else if (isResumption)
        {
            return createResumptionProjection(frame, initialPacket, mark, newDt, receivedTime);
        }

        return createInEncodingProjection(frame, initialPacket, mark, newDt, receivedTime);
    }

    /**
     * Create a projection for the first frame after an encoding switch.
     */
    @NotNull
    private Av1DDFrameProjection createEncodingSwitchProjection(
        @NotNull Av1DDFrame frame,
        @NotNull Av1DDPacket initialPacket,
        boolean mark,
        @Nullable Integer newDt,
        @Nullable Instant receivedTime)
    {
        // We can only switch on packets that carry a scalability structure, which is the first packet of a keyframe
        assert frame.isKeyframe();
        assert initialPacket.isStartOfFrame();
        lastFrameNumberIndexResumption = frame.getIndex();

        int projectedSeqGap = 1;

        if (lastAv1FrameProjection.getAv1Frame() != null && !lastAv1FrameProjection.getAv1Frame().hasSeenEndOfFrame())
        {
            /* Leave a gap to signal to the decoder that the previously routed
               frame was incomplete. */
            projectedSeqGap++;

            /* Make sure subsequent packets of the previous projection won't
               overlap the new one.  (This means the gap, above, will never be
               filled in.)
             */
            lastAv1FrameProjection.close();
        }

        int projectedSeq =
            RtpUtils.applySequenceNumberDelta(lastAv1FrameProjection.getLatestProjectedSeqNum(), projectedSeqGap);

        // this is a simulcast switch. The typical incremental value =
        // 90kHz / 30 = 90,000Hz / 30 = 3000 per frame or per 33ms
        long tsDelta;
        if (lastAv1FrameProjection.getCreated() != null && receivedTime != null)
        {
            long secs = Duration.between(lastAv1FrameProjection.getCreated(), receivedTime).dividedBy(33).getSeconds();
            tsDelta = 3000 * Math.max(secs, 1L);
        }
        else
        {
            tsDelta = 3000;
        }
        long projectedTs = RtpUtils.applyTimestampDelta(lastAv1FrameProjection.getTimestamp(), tsDelta);

        int frameNumber;
        int templateIdDelta;
        Integer nextTemplateId = lastAv1FrameProjection.getNextTemplateId();
        if (nextTemplateId != null)
        {
            frameNumber = RtpUtils.applySequenceNumberDelta(lastAv1FrameProjection.getFrameNumber(), 1);
            Av1TemplateDependencyStructure structure = frame.getStructure();
            if (structure == null)
            {
                throw new IllegalStateException("Keyframe frame has no structure");
            }
            templateIdDelta = Av1DDPacket.getTemplateIdDelta(nextTemplateId, structure.getTemplateIdOffset());
        }
        else
        {
            frameNumber = frame.getFrameNumber();
            templateIdDelta = 0;
        }

        return new Av1DDFrameProjection(
            diagnosticContext,
            frame,
            lastAv1FrameProjection.getSSRC(),
            projectedTs,
            RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.getSequenceNumber()),
            frameNumber,
            templateIdDelta,
            dtBitmaskForNewDt(frame, newDt),
            mark,
            receivedTime);
    }

    /**
     * Create a projection for the first frame after a resumption, i.e. when a source is turned back on.
     */
    @NotNull
    private Av1DDFrameProjection createResumptionProjection(
        @NotNull Av1DDFrame frame,
        @NotNull Av1DDPacket initialPacket,
        boolean mark,
        @Nullable Integer newDt,
        @Nullable Instant receivedTime)
    {
        lastFrameNumberIndexResumption = frame.getIndex();

        /* These must be non-null because we don't execute this function unless
            frameIsNewSsrc and isReset were both false.
         */
        Av1DDFrame lastFrame = prevFrame(frame);
        Av1DDFrame lastProjectedFrame = lastAv1FrameProjection.getAv1Frame();

        /* Project timestamps linearly. */
        long tsDelta = RtpUtils.getTimestampDiff(lastAv1FrameProjection.getTimestamp(), lastProjectedFrame.getTimestamp());
        long projectedTs = RtpUtils.applyTimestampDelta(frame.getTimestamp(), tsDelta);

        /* Increment frameNumber by 1 from the last projected frame. */
        int projectedFrameNumber = RtpUtils.applySequenceNumberDelta(lastAv1FrameProjection.getFrameNumber(), 1);

        /* If this packet has a template structure, rewrite it to follow after the pre-resumption structure.
         * (We could check to see if the structure is unchanged, but that's an unnecessary optimization.)
         */
        int templateIdDelta;
        if (frame.getStructure() != null)
        {
            Integer nextTemplateId = lastAv1FrameProjection.getNextTemplateId();
            if (nextTemplateId != null)
            {
                Av1TemplateDependencyStructure structure = frame.getStructure();
                templateIdDelta = Av1DDPacket.getTemplateIdDelta(nextTemplateId, structure.getTemplateIdOffset());
            }
            else
            {
                templateIdDelta = 0;
            }
        }
        else
        {
            templateIdDelta = 0;
        }

        /* Increment sequence numbers based on the last projected frame, but leave a gap
         * for packet reordering in case this isn't the first packet of the keyframe.
         */
        int seqGap = RtpUtils.getSequenceNumberDelta(
            initialPacket.getSequenceNumber(), lastFrame.getLatestKnownSequenceNumber());
        int newSeq = RtpUtils.applySequenceNumberDelta(lastAv1FrameProjection.getLatestProjectedSeqNum(), seqGap);
        int seqDelta = RtpUtils.getSequenceNumberDelta(newSeq, initialPacket.getSequenceNumber());

        return new Av1DDFrameProjection(
            diagnosticContext,
            frame,
            lastAv1FrameProjection.getSSRC(),
            projectedTs,
            seqDelta,
            projectedFrameNumber,
            templateIdDelta,
            dtBitmaskForNewDt(frame, newDt),
            mark,
            receivedTime);
    }

    /**
     * Create a projection for the first frame after a frame reset, i.e. after a large gap in sequence numbers.
     */
    @NotNull
    private Av1DDFrameProjection createResetProjection(
        @NotNull Av1DDFrame frame,
        @NotNull Av1DDPacket initialPacket,
        boolean mark,
        @Nullable Integer newDt,
        @Nullable Instant receivedTime)
    {
        /* This must be non-null because we don't execute this function unless
            frameIsNewSsrc has returned false.
         */
        Av1DDFrame lastFrame = lastAv1FrameProjection.getAv1Frame();

        /* Apply the latest projected frame's projections out, linearly. */
        int seqDelta = RtpUtils.getSequenceNumberDelta(
            lastAv1FrameProjection.getLatestProjectedSeqNum(), lastFrame.getLatestKnownSequenceNumber());
        long tsDelta = RtpUtils.getTimestampDiff(lastAv1FrameProjection.getTimestamp(), lastFrame.getTimestamp());
        int frameNumberDelta = RtpUtils.applySequenceNumberDelta(
            lastAv1FrameProjection.getFrameNumber(), lastFrame.getFrameNumber());

        long projectedTs = RtpUtils.applyTimestampDelta(frame.getTimestamp(), tsDelta);
        int projectedFrameNumber = RtpUtils.applySequenceNumberDelta(frame.getFrameNumber(), frameNumberDelta);

        /* If this packet has a template structure, rewrite it to follow after the pre-reset structure.
         * (We could check to see if the structure is unchanged, but that's an unnecessary optimization.)
         */
        int templateIdDelta;
        if (frame.getStructure() != null)
        {
            Integer nextTemplateId = lastAv1FrameProjection.getNextTemplateId();
            if (nextTemplateId != null)
            {
                Av1TemplateDependencyStructure structure = frame.getStructure();
                templateIdDelta = Av1DDPacket.getTemplateIdDelta(nextTemplateId, structure.getTemplateIdOffset());
            }
            else
            {
                templateIdDelta = 0;
            }
        }
        else
        {
            templateIdDelta = 0;
        }

        return new Av1DDFrameProjection(
            diagnosticContext,
            frame,
            lastAv1FrameProjection.getSSRC(),
            projectedTs,
            seqDelta,
            projectedFrameNumber,
            templateIdDelta,
            dtBitmaskForNewDt(frame, newDt),
            mark,
            receivedTime);
    }

    /**
     * Create a frame projection for the normal case, i.e. as part of the same encoding as the
     * previously-projected frame.
     */
    @NotNull
    private Av1DDFrameProjection createInEncodingProjection(
        @NotNull Av1DDFrame frame,
        @NotNull Av1DDPacket initialPacket,
        boolean mark,
        @Nullable Integer newDt,
        @Nullable Instant receivedTime)
    {
        Av1DDFrame prevFrame = findPrevAcceptedFrame(frame);
        if (prevFrame != null)
        {
            return createInEncodingProjection(frame, prevFrame, initialPacket, mark, newDt, receivedTime);
        }

        /* prev frame has rolled off beginning of frame map, try next frame */
        Av1DDFrame nextFrame = findNextAcceptedFrame(frame);
        if (nextFrame != null)
        {
            return createInEncodingProjection(frame, nextFrame, initialPacket, mark, newDt, receivedTime);
        }

        /* Neither previous or next is found. Very big frame? Use previous projected.
           (This must be valid because we don't execute this function unless
           frameIsNewSsrc has returned false.)
         */
        return createInEncodingProjection(
            frame, lastAv1FrameProjection.getAv1Frame(), initialPacket, mark, newDt, receivedTime);
    }

    /**
     * Create a frame projection for the normal case, i.e. as part of the same encoding as the
     * previously-projected frame, based on a specific chosen previously-projected frame.
     */
    @NotNull
    private Av1DDFrameProjection createInEncodingProjection(
        @NotNull Av1DDFrame frame,
        @NotNull Av1DDFrame refFrame,
        @NotNull Av1DDPacket initialPacket,
        boolean mark,
        @Nullable Integer newDt,
        @Nullable Instant receivedTime)
    {
        long tsGap = RtpUtils.getTimestampDiff(frame.getTimestamp(), refFrame.getTimestamp());
        int frameNumGap = RtpUtils.getSequenceNumberDelta(frame.getFrameNumber(), refFrame.getFrameNumber());
        int seqGap = 0;

        Av1DDFrame f1 = refFrame;
        Av1DDFrame f2;
        int refSeq;
        if (frameNumGap > 0)
        {
            /* refFrame is earlier than frame in decode order. */
            do
            {
                f2 = nextFrame(f1);
                if (f2 == null)
                {
                    throw new IllegalStateException("No next frame found after frame with frame number "
                        + f1.getFrameNumber() + ", even though refFrame " + refFrame.getFrameNumber()
                        + " is before frame " + frame.getFrameNumber() + "!");
                }
                seqGap += seqGap(f1, f2);
                f1 = f2;
            }
            while (f2 != frame);
            /* refFrame is a projected frame, so it has a projection. */
            refSeq = refFrame.getProjection().getLatestProjectedSeqNum();
        }
        else
        {
            /* refFrame is later than frame in decode order. */
            do
            {
                f2 = prevFrame(f1);
                if (f2 == null)
                {
                    throw new IllegalStateException("No previous frame found before frame with frame number "
                        + f1.getFrameNumber() + ", even though refFrame " + refFrame.getFrameNumber()
                        + " is after frame " + frame.getFrameNumber() + "!");
                }
                seqGap += -seqGap(f2, f1);
                f1 = f2;
            }
            while (f2 != frame);
            refSeq = refFrame.getProjection().getEarliestProjectedSeqNum();
        }

        int projectedSeq = RtpUtils.applySequenceNumberDelta(refSeq, seqGap);
        long projectedTs = RtpUtils.applyTimestampDelta(refFrame.getProjection().getTimestamp(), tsGap);
        int projectedFrameNumber =
            RtpUtils.applySequenceNumberDelta(refFrame.getProjection().getFrameNumber(), frameNumGap);

        return new Av1DDFrameProjection(
            diagnosticContext,
            frame,
            lastAv1FrameProjection.getSSRC(),
            projectedTs,
            RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.getSequenceNumber()),
            projectedFrameNumber,
            lastAv1FrameProjection.getTemplateIdDelta(),
            dtBitmaskForNewDt(frame, newDt),
            mark,
            receivedTime);
    }

    @Nullable
    private static Integer dtBitmaskForNewDt(@NotNull Av1DDFrame frame, @Nullable Integer newDt)
    {
        if (newDt == null)
        {
            return null;
        }
        Av1TemplateDependencyStructure structure = frame.getStructure();
        return structure != null ? structure.getDtBitmaskForDt(newDt) : null;
    }

    @Override
    public boolean needsKeyframe()
    {
        if (av1QualityFilter.needsKeyframe())
        {
            return true;
        }

        return lastAv1FrameProjection.getAv1Frame() == null;
    }

    @Override
    public void rewriteRtp(@NotNull PacketInfo packetInfo) throws RewriteException
    {
        if (!(packetInfo.getPacket() instanceof Av1DDPacket))
        {
            logger.info("Got a non-AV1 DD packet.");
            throw new RewriteException("Non-AV1 DD packet in AV1 DD source projection");
        }
        Av1DDPacket av1Packet = packetInfo.packetAs();

        Av1DDFrame av1Frame = lookupAv1Frame(av1Packet);
        if (av1Frame == null)
        {
            throw new RewriteException("Frame not in tracker (aged off?)");
        }

        Av1DDFrameProjection av1Projection = av1Frame.getProjection();
        if (av1Projection == null)
        {
            /* Shouldn't happen for an accepted packet whose frame is still known? */
            throw new RewriteException("Frame does not have projection?");
        }

        logger.trace(() -> "Rewriting packet with structure " + System.identityHashCode(
            av1Packet.getDescriptor() != null ? av1Packet.getDescriptor().getStructure() : null));
        av1Projection.rewriteRtp(av1Packet);
    }

    @Override
    public boolean rewriteRtcp(@NotNull RtcpSrPacket rtcpSrPacket)
    {
        Av1DDFrameProjection lastAv1FrameProjectionCopy = lastAv1FrameProjection;
        Av1DDFrame lastFrame = lastAv1FrameProjectionCopy.getAv1Frame();
        if (lastFrame == null || rtcpSrPacket.getSenderSsrc() != lastFrame.getSsrc())
        {
            return false;
        }

        rtcpSrPacket.setSenderSsrc(lastAv1FrameProjectionCopy.getSSRC());

        long srcTs = rtcpSrPacket.getSenderInfo().getRtpTimestamp();
        long delta = RtpUtils.getTimestampDiff(lastAv1FrameProjectionCopy.getTimestamp(), lastFrame.getTimestamp());

        long dstTs = RtpUtils.applyTimestampDelta(srcTs, delta);

        if (srcTs != dstTs)
        {
            rtcpSrPacket.getSenderInfo().setRtpTimestamp(dstTs);
        }

        return true;
    }

    @Override
    public RtpState getRtpState()
    {
        return new RtpState(
            lastAv1FrameProjection.getSSRC(),
            lastAv1FrameProjection.getLatestProjectedSeqNum(),
            lastAv1FrameProjection.getTimestamp());
    }

    @Override
    @NotNull
    public Object getPersistentState()
    {
        Integer nextTemplateId = lastAv1FrameProjection.getNextTemplateId();
        return new Av1PersistentState(
            lastAv1FrameProjection.getFrameNumber(),
            nextTemplateId != null ? nextTemplateId : 0);
    }

    @Override
    public ObjectNode getDebugState()
    {
        ObjectNode debugState = JsonNodeFactory.instance.objectNode();
        debugState.put("class", Av1DDAdaptiveSourceProjectionContext.class.getSimpleName());

        ArrayNode mapSizes = JsonNodeFactory.instance.arrayNode();
        for (Map.Entry<Long, Av1DDFrameMap> entry : av1FrameMaps.entrySet())
        {
            ObjectNode sizeInfo = JsonNodeFactory.instance.objectNode();
            sizeInfo.put("ssrc", entry.getKey());
            sizeInfo.put("size", entry.getValue().size());
            mapSizes.add(sizeInfo);
        }
        debugState.set("av1FrameMaps", mapSizes);
        debugState.set("av1QualityFilter", av1QualityFilter.getDebugState());

        return debugState;
    }

    /**
     * Persistent state carried between successive {@link Av1DDAdaptiveSourceProjectionContext} instances for the
     * same {@link org.jitsi.videobridge.cc.AdaptiveSourceProjection}, so that frame numbers and template IDs
     * continue monotonically across context switches.
     */
    public static class Av1PersistentState
    {
        private final int frameNumber;
        private final int templateId;

        public Av1PersistentState(int frameNumber, int templateId)
        {
            this.frameNumber = frameNumber;
            this.templateId = templateId;
        }

        public int getFrameNumber()
        {
            return frameNumber;
        }

        public int getTemplateId()
        {
            return templateId;
        }
    }
}
