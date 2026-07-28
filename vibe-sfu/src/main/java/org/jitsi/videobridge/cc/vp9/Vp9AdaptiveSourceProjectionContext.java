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
package org.jitsi.videobridge.cc.vp9;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.codec.vpx.VpxUtils;
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet;
import org.jitsi.rtp.rtcp.RtcpSrPacket;
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
import java.util.Map;

/**
 * This class represents a projection of a VP9 RTP stream
 * and it is the main entry point for VP9 simulcast/svc RTP/RTCP rewriting. Read
 * svc.md for implementation details. Instances of this class are thread-safe.
 */
public class Vp9AdaptiveSourceProjectionContext implements AdaptiveSourceProjectionContext
{
    /**
     * The time series logger for this class.
     */
    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(Vp9AdaptiveSourceProjectionContext.class);

    private final DiagnosticContext diagnosticContext;

    private final Logger logger;

    /**
     * A map that stores the per-encoding VP9 picture maps.
     */
    private final Map<Long, Vp9PictureMap> vp9PictureMaps = new HashMap<>();

    /**
     * The {@link Vp9QualityFilter} instance that does quality filtering on the
     * incoming pictures, to choose encodings and layers to forward.
     */
    private final Vp9QualityFilter vp9QualityFilter;

    private Vp9FrameProjection lastVp9FrameProjection;

    /**
     * The picture ID index that started the latest stream resumption.
     * We can't send frames with picIdIdx less than this, because we don't have
     * space in the projected sequence number/picId/tl0PicIdx counts.
     */
    private long lastPicIdIndexResumption = -1L;

    public Vp9AdaptiveSourceProjectionContext(
        @NotNull DiagnosticContext diagnosticContext,
        @NotNull RtpState rtpState,
        @NotNull Logger parentLogger)
    {
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(Vp9AdaptiveSourceProjectionContext.class.getName());
        this.vp9QualityFilter = new Vp9QualityFilter(logger);

        this.lastVp9FrameProjection = new Vp9FrameProjection(
            diagnosticContext, rtpState.ssrc, rtpState.maxSequenceNumber, rtpState.maxTimestamp);
    }

    @Override
    public synchronized boolean accept(@NotNull PacketInfo packetInfo, int targetIndex)
    {
        if (!(packetInfo.getPacket() instanceof Vp9Packet))
        {
            logger.warn("Packet is not Vp9 packet");
            return false;
        }
        Vp9Packet packet = packetInfo.packetAs();
        int incomingEncoding = packet.getEncodingId();

        /* If insertPacketInMap returns null, this is a very old picture, more than Vp9PictureMap.PICTURE_MAP_SIZE
           old, or something is wrong with the stream. */
        Vp9Picture.PacketInsertionResult result = insertPacketInMap(packet);
        if (result == null)
        {
            return false;
        }

        Vp9Frame frame = result.getFrame();

        if (result.isNewFrame())
        {
            if (packet.isKeyframe() && frameIsNewSsrc(frame))
            {
                /* If we're not currently projecting this SSRC, check if we've
                 * already decided to drop a subsequent base TL0 frame of this SSRC.
                 * If we have, we can't turn on the encoding starting from this
                 * packet, so treat this frame as though it weren't a keyframe.
                 */
                Vp9Frame f = findNextBaseTl0(frame);
                if (f != null && !f.isAccepted())
                {
                    frame.setKeyframe(false);
                }
            }
            Instant receivedTime = packetInfo.getReceivedTime();
            Vp9QualityFilter.AcceptResult acceptResult =
                vp9QualityFilter.acceptFrame(frame, incomingEncoding, targetIndex, receivedTime);
            frame.setAccepted(acceptResult.isAccept() && frameIsProjectable(frame));
            if (frame.isAccepted())
            {
                Vp9FrameProjection projection;
                try
                {
                    projection = createProjection(
                        frame, packet, acceptResult.isMark(), acceptResult.isResumption(), result.isReset(),
                        receivedTime);
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
                    projection.getEarliestProjectedSeqNum(), lastVp9FrameProjection.getLatestProjectedSeqNum()))
                {
                    lastVp9FrameProjection = projection;
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
            DiagnosticContext.TimeSeriesPoint pt = diagnosticContext.makeTimeSeriesPoint("rtp_vp9")
                .addField("ssrc", packet.getSsrc())
                .addField("timestamp", packet.getTimestamp())
                .addField("seq", packet.getSequenceNumber())
                .addField("pictureId", packet.getPictureId())
                .addField("pictureIdIndex", frame.getIndex())
                .addField("encoding", incomingEncoding)
                .addField("keyframe", packet.isKeyframe())
                .addField("spatialLayer", packet.getSpatialLayerIndex())
                .addField("temporalLayer", packet.getTemporalLayerIndex())
                .addField("isInterPicturePredicted", packet.isInterPicturePredicted())
                .addField("usesInterLayerDependency", packet.usesInterLayerDependency())
                .addField("isUpperLevelReference", packet.isUpperLevelReference())
                .addField("startOfFrame", packet.isStartOfFrame())
                .addField("endOfFrame", packet.isEndOfFrame())
                .addField("mark", packet.isMarked())
                .addField("targetIndex", RtpLayerDesc.indexString(targetIndex))
                .addField("new_frame", result.isNewFrame())
                .addField("reset", result.isReset())
                .addField("accept", accept);
            vp9QualityFilter.addDiagnosticContext(pt);
            timeSeriesLogger.trace(pt);
        }

        return accept;
    }

    /** Look up a Vp9Frame for a packet. */
    @Nullable
    private Vp9Frame lookupVp9Frame(@NotNull Vp9Packet vp9Packet)
    {
        Vp9PictureMap pictureMap = vp9PictureMaps.get(vp9Packet.getSsrc());
        if (pictureMap == null)
        {
            return null;
        }
        Vp9Picture picture = pictureMap.findPicture(vp9Packet);
        if (picture == null)
        {
            return null;
        }
        return picture.frame(vp9Packet);
    }

    /**
     * Insert a packet in the appropriate Vp9PictureMap.
     */
    @Nullable
    private Vp9Picture.PacketInsertionResult insertPacketInMap(@NotNull Vp9Packet vp9Packet)
    {
        Vp9PictureMap pictureMap = vp9PictureMaps.computeIfAbsent(
            vp9Packet.getSsrc(), ssrc -> new Vp9PictureMap(logger));
        return pictureMap.insertPacket(vp9Packet);
    }

    /**
     * Calculate the projected sequence number gap between two frames (of the same encoding),
     * allowing collapsing for unaccepted frames.
     */
    private int seqGap(@NotNull Vp9Frame frame1, @NotNull Vp9Frame frame2)
    {
        int seqGap = RtpUtils.getSequenceNumberDelta(
            frame2.getEarliestKnownSequenceNumber(), frame1.getLatestKnownSequenceNumber());

        if (!frame1.isAccepted() && !frame2.isAccepted() && frame2.isImmediatelyAfter(frame1))
        {
            /* If neither frame is being projected, and they have consecutive
               picture IDs, we don't need to leave any gap. */
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

    private boolean frameIsNewSsrc(@NotNull Vp9Frame frame)
    {
        Vp9Frame lastFrame = lastVp9FrameProjection.getVp9Frame();
        return lastFrame == null || !lastFrame.matchesSSRC(frame);
    }

    private boolean frameIsProjectable(@NotNull Vp9Frame frame)
    {
        return frameIsNewSsrc(frame) || frame.getIndex() >= lastPicIdIndexResumption;
    }

    /**
     * Find the previous frame before the given one.
     */
    @Nullable
    private synchronized Vp9Frame prevFrame(@NotNull Vp9Frame frame)
    {
        Vp9PictureMap pictureMap = vp9PictureMaps.get(frame.getSsrc());
        return pictureMap == null ? null : pictureMap.prevFrame(frame);
    }

    /**
     * Find the next frame after the given one.
     */
    @Nullable
    private synchronized Vp9Frame nextFrame(@NotNull Vp9Frame frame)
    {
        Vp9PictureMap pictureMap = vp9PictureMaps.get(frame.getSsrc());
        return pictureMap == null ? null : pictureMap.nextFrame(frame);
    }

    /**
     * Find the previous accepted frame before the given one.
     */
    @Nullable
    private Vp9Frame findPrevAcceptedFrame(@NotNull Vp9Frame frame)
    {
        Vp9PictureMap pictureMap = vp9PictureMaps.get(frame.getSsrc());
        return pictureMap == null ? null : pictureMap.findPrevAcceptedFrame(frame);
    }

    /**
     * Find the next accepted frame after the given one.
     */
    @Nullable
    private Vp9Frame findNextAcceptedFrame(@NotNull Vp9Frame frame)
    {
        Vp9PictureMap pictureMap = vp9PictureMaps.get(frame.getSsrc());
        return pictureMap == null ? null : pictureMap.findNextAcceptedFrame(frame);
    }

    /**
     * Find a subsequent base-layer TL0 frame after the given frame
     * @param frame The frame to query
     * @return A subsequent base-layer TL0 frame, or null
     */
    @Nullable
    private Vp9Frame findNextBaseTl0(@NotNull Vp9Frame frame)
    {
        Vp9PictureMap pictureMap = vp9PictureMaps.get(frame.getSsrc());
        return pictureMap == null ? null : pictureMap.findNextBaseTl0(frame);
    }

    /**
     * Create a projection for this frame.
     */
    @NotNull
    private Vp9FrameProjection createProjection(
        @NotNull Vp9Frame frame,
        @NotNull Vp9Packet initialPacket,
        boolean mark,
        boolean isResumption,
        boolean isReset,
        @Nullable Instant receivedTime)
    {
        if (frameIsNewSsrc(frame))
        {
            return createEncodingSwitchProjection(frame, initialPacket, mark, receivedTime);
        }
        else if (isReset)
        {
            return createResetProjection(frame, initialPacket, mark, receivedTime);
        }
        else if (isResumption)
        {
            return createResumptionProjection(frame, initialPacket, mark, receivedTime);
        }

        return createInEncodingProjection(frame, initialPacket, mark, receivedTime);
    }

    /**
     * Create a projection for the first frame after an encoding switch.
     */
    @NotNull
    private Vp9FrameProjection createEncodingSwitchProjection(
        @NotNull Vp9Frame frame,
        @NotNull Vp9Packet initialPacket,
        boolean mark,
        @Nullable Instant receivedTime)
    {
        assert frame.isKeyframe();
        lastPicIdIndexResumption = frame.getIndex();

        int projectedSeqGap;
        if (!initialPacket.isStartOfFrame())
        {
            Vp9Frame f = prevFrame(frame);
            if (f != null)
            {
                /* Leave enough of a gap to fill in the earlier packets of the keyframe */
                projectedSeqGap = seqGap(f, frame);
            }
            else
            {
                /* If this is the first packet we've seen on this encoding, and it's not the start of a frame,
                 * we have a problem - we don't know where this packet might fall in the frame.
                 * Guess a reasonably-sized guess for the number of packets that might have been dropped, and hope
                 * we don't end up reusing sequence numbers.
                 */
                projectedSeqGap = 16;
            }
        }
        else
        {
            projectedSeqGap = 1;
        }

        if (lastVp9FrameProjection.getVp9Frame() != null && !lastVp9FrameProjection.getVp9Frame().hasSeenEndOfFrame())
        {
            /* Leave a gap to signal to the decoder that the previously routed
               frame was incomplete. */
            projectedSeqGap++;

            /* Make sure subsequent packets of the previous projection won't
               overlap the new one.  (This means the gap, above, will never be
               filled in.)
             */
            lastVp9FrameProjection.close();
        }

        int projectedSeq =
            RtpUtils.applySequenceNumberDelta(lastVp9FrameProjection.getLatestProjectedSeqNum(), projectedSeqGap);

        // this is a simulcast switch. The typical incremental value =
        // 90kHz / 30 = 90,000Hz / 30 = 3000 per frame or per 33ms
        long tsDelta;
        if (lastVp9FrameProjection.getCreated() != null && receivedTime != null)
        {
            tsDelta = 3000 * Math.max(1,
                Duration.between(lastVp9FrameProjection.getCreated(), receivedTime).dividedBy(33).getSeconds());
        }
        else
        {
            tsDelta = 3000;
        }
        long projectedTs = RtpUtils.applyTimestampDelta(lastVp9FrameProjection.getTimestamp(), tsDelta);

        int picId;
        int tl0PicIdx;
        if (lastVp9FrameProjection.getVp9Frame() != null)
        {
            picId = VpxUtils.applyExtendedPictureIdDelta(lastVp9FrameProjection.getPictureId(), 1);
            tl0PicIdx = VpxUtils.applyTl0PicIdxDelta(lastVp9FrameProjection.getTl0PICIDX(), 1);
        }
        else
        {
            picId = frame.getPictureId();
            tl0PicIdx = frame.getTl0PICIDX();
        }

        return new Vp9FrameProjection(
            diagnosticContext,
            frame,
            lastVp9FrameProjection.getSSRC(),
            projectedTs,
            RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.getSequenceNumber()),
            picId,
            tl0PicIdx,
            mark,
            receivedTime);
    }

    /**
     * Create a projection for the first frame after a resumption, i.e. when a source is turned back on.
     */
    @NotNull
    private Vp9FrameProjection createResumptionProjection(
        @NotNull Vp9Frame frame,
        @NotNull Vp9Packet initialPacket,
        boolean mark,
        @Nullable Instant receivedTime)
    {
        lastPicIdIndexResumption = frame.getIndex();

        /* These must be non-null because we don't execute this function unless
            frameIsNewSsrc and isReset were both false.
         */
        Vp9Frame lastFrame = prevFrame(frame);
        Vp9Frame lastProjectedFrame = lastVp9FrameProjection.getVp9Frame();

        /* Project timestamps linearly. */
        long tsDelta = RtpUtils.getTimestampDiff(lastVp9FrameProjection.getTimestamp(), lastProjectedFrame.getTimestamp());
        long projectedTs = RtpUtils.applyTimestampDelta(frame.getTimestamp(), tsDelta);

        /* Increment picId and tl0picidx by 1 from the last projected frame. */
        int projectedPicId = VpxUtils.applyExtendedPictureIdDelta(lastVp9FrameProjection.getPictureId(), 1);
        int projectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(lastVp9FrameProjection.getTl0PICIDX(), 1);

        /* Increment sequence numbers based on the last projected frame, but leave a gap
         * for packet reordering in case this isn't the first packet of the keyframe.
         */
        int seqGap = RtpUtils.getSequenceNumberDelta(
            initialPacket.getSequenceNumber(), lastFrame.getLatestKnownSequenceNumber());
        int newSeq = RtpUtils.applySequenceNumberDelta(lastVp9FrameProjection.getLatestProjectedSeqNum(), seqGap);
        int seqDelta = RtpUtils.getSequenceNumberDelta(newSeq, initialPacket.getSequenceNumber());

        return new Vp9FrameProjection(
            diagnosticContext,
            frame, lastVp9FrameProjection.getSSRC(), projectedTs,
            seqDelta,
            projectedPicId, projectedTl0PicIdx, mark, receivedTime);
    }

    /**
     * Create a projection for the first frame after a frame reset, i.e. after a large gap in sequence numbers.
     */
    @NotNull
    private Vp9FrameProjection createResetProjection(
        @NotNull Vp9Frame frame,
        @NotNull Vp9Packet initialPacket,
        boolean mark,
        @Nullable Instant receivedTime)
    {
        /* This must be non-null because we don't execute this function unless
            frameIsNewSsrc has returned false.
         */
        Vp9Frame lastFrame = lastVp9FrameProjection.getVp9Frame();

        /* Apply the latest projected frame's projections out, linearly. */
        int seqDelta = RtpUtils.getSequenceNumberDelta(
            lastVp9FrameProjection.getLatestProjectedSeqNum(), lastFrame.getLatestKnownSequenceNumber());
        long tsDelta = RtpUtils.getTimestampDiff(lastVp9FrameProjection.getTimestamp(), lastFrame.getTimestamp());
        int picIdDelta = VpxUtils.getExtendedPictureIdDelta(
            lastVp9FrameProjection.getPictureId(), lastFrame.getPictureId());
        int tl0PicIdxDelta = VpxUtils.getTl0PicIdxDelta(
            lastVp9FrameProjection.getTl0PICIDX(), lastFrame.getTl0PICIDX());

        long projectedTs = RtpUtils.applyTimestampDelta(frame.getTimestamp(), tsDelta);
        int projectedPicId = VpxUtils.applyExtendedPictureIdDelta(frame.getPictureId(), picIdDelta);
        int projectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(frame.getTl0PICIDX(), tl0PicIdxDelta);

        return new Vp9FrameProjection(
            diagnosticContext,
            frame, lastVp9FrameProjection.getSSRC(), projectedTs,
            seqDelta,
            projectedPicId, projectedTl0PicIdx, mark, receivedTime);
    }

    /**
     * Create a frame projection for the normal case, i.e. as part of the same encoding as the
     * previously-projected frame.
     */
    @NotNull
    private Vp9FrameProjection createInEncodingProjection(
        @NotNull Vp9Frame frame,
        @NotNull Vp9Packet initialPacket,
        boolean mark,
        @Nullable Instant receivedTime)
    {
        Vp9Frame prevFrame = findPrevAcceptedFrame(frame);
        if (prevFrame != null)
        {
            return createInEncodingProjection(frame, prevFrame, initialPacket, mark, receivedTime);
        }

        /* prev frame has rolled off beginning of frame map, try next frame */
        Vp9Frame nextFrame = findNextAcceptedFrame(frame);
        if (nextFrame != null)
        {
            return createInEncodingProjection(frame, nextFrame, initialPacket, mark, receivedTime);
        }

        /* Neither previous or next is found. Very big frame? Use previous projected.
           (This must be valid because we don't execute this function unless
           frameIsNewSsrc has returned false.)
         */
        return createInEncodingProjection(
            frame, lastVp9FrameProjection.getVp9Frame(), initialPacket, mark, receivedTime);
    }

    /**
     * Create a frame projection for the normal case, i.e. as part of the same encoding as the
     * previously-projected frame, based on a specific chosen previously-projected frame.
     */
    @NotNull
    private Vp9FrameProjection createInEncodingProjection(
        @NotNull Vp9Frame frame,
        @NotNull Vp9Frame refFrame,
        @NotNull Vp9Packet initialPacket,
        boolean mark,
        @Nullable Instant receivedTime)
    {
        long tsGap = RtpUtils.getTimestampDiff(frame.getTimestamp(), refFrame.getTimestamp());
        int tl0Gap = VpxUtils.getTl0PicIdxDelta(frame.getTl0PICIDX(), refFrame.getTl0PICIDX());
        int picGap = VpxUtils.getExtendedPictureIdDelta(frame.getPictureId(), refFrame.getPictureId());
        int layerGap = frame.getSpatialLayer() - refFrame.getSpatialLayer();
        int seqGap = 0;

        Vp9Frame f1 = refFrame;
        Vp9Frame f2;
        int refSeq;
        if (picGap > 0 || (picGap == 0 && layerGap > 0))
        {
            /* refFrame is earlier than frame in decode order. */
            do
            {
                f2 = nextFrame(f1);
                if (f2 == null)
                {
                    throw new IllegalStateException("No next frame found after frame with picId " + f1.getPictureId()
                        + " layer " + f1.getSpatialLayer() + ", even though refFrame " + refFrame.getPictureId()
                        + "/" + refFrame.getSpatialLayer() + " is before frame " + frame.getPictureId() + "/"
                        + frame.getSpatialLayer() + "!");
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
                    throw new IllegalStateException("No previous frame found before frame with picId "
                        + f1.getPictureId() + " layer " + f1.getSpatialLayer() + ", even though refFrame "
                        + refFrame.getPictureId() + "/" + refFrame.getSpatialLayer() + " is after frame "
                        + frame.getPictureId() + "/" + frame.getSpatialLayer() + "!");
                }
                seqGap += -seqGap(f2, f1);
                f1 = f2;
            }
            while (f2 != frame);
            refSeq = refFrame.getProjection().getEarliestProjectedSeqNum();
        }

        int projectedSeq = RtpUtils.applySequenceNumberDelta(refSeq, seqGap);
        long projectedTs = RtpUtils.applyTimestampDelta(refFrame.getProjection().getTimestamp(), tsGap);
        int projectedPicId = VpxUtils.applyExtendedPictureIdDelta(refFrame.getProjection().getPictureId(), picGap);
        int projectedTl0PicIdx =
            VpxUtils.applyTl0PicIdxDelta(refFrame.getProjection().getTl0PICIDX(), tl0Gap);

        return new Vp9FrameProjection(
            diagnosticContext,
            frame,
            lastVp9FrameProjection.getSSRC(),
            projectedTs,
            RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.getSequenceNumber()),
            projectedPicId,
            projectedTl0PicIdx,
            mark,
            receivedTime);
    }

    @Override
    public boolean needsKeyframe()
    {
        if (vp9QualityFilter.needsKeyframe())
        {
            return true;
        }

        return lastVp9FrameProjection.getVp9Frame() == null;
    }

    @Override
    public void rewriteRtp(@NotNull PacketInfo packetInfo) throws RewriteException
    {
        if (!(packetInfo.getPacket() instanceof Vp9Packet))
        {
            logger.info("Got a non-VP9 packet.");
            throw new RewriteException("Non-VP9 packet in VP9 source projection");
        }
        Vp9Packet vp9Packet = packetInfo.packetAs();

        if (vp9Packet.getPictureId() == -1)
        {
            /* Should have been routed to generic projection context. */
            logger.info("VP9 packet does not have picture ID, cannot track in frame map.");
            throw new RewriteException("VP9 packet without picture ID in VP9 source projection");
        }

        Vp9Frame vp9Frame = lookupVp9Frame(vp9Packet);
        if (vp9Frame == null)
        {
            throw new RewriteException("Frame not in tracker (aged off?)");
        }

        Vp9FrameProjection vp9Projection = vp9Frame.getProjection();
        if (vp9Projection == null)
        {
            /* Shouldn't happen for an accepted packet whose frame is still known? */
            throw new RewriteException("Frame does not have projection?");
        }

        vp9Projection.rewriteRtp(vp9Packet);
    }

    @Override
    public boolean rewriteRtcp(@NotNull RtcpSrPacket rtcpSrPacket)
    {
        Vp9FrameProjection lastVp9FrameProjectionCopy = lastVp9FrameProjection;
        Vp9Frame lastFrame = lastVp9FrameProjectionCopy.getVp9Frame();
        if (lastFrame == null || rtcpSrPacket.getSenderSsrc() != lastFrame.getSsrc())
        {
            return false;
        }

        rtcpSrPacket.setSenderSsrc(lastVp9FrameProjectionCopy.getSSRC());

        long srcTs = rtcpSrPacket.getSenderInfo().getRtpTimestamp();
        long delta = RtpUtils.getTimestampDiff(lastVp9FrameProjectionCopy.getTimestamp(), lastFrame.getTimestamp());

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
            lastVp9FrameProjection.getSSRC(),
            lastVp9FrameProjection.getLatestProjectedSeqNum(),
            lastVp9FrameProjection.getTimestamp());
    }

    @Override
    public synchronized ObjectNode getDebugState()
    {
        ObjectNode debugState = JsonNodeFactory.instance.objectNode();
        debugState.put("class", Vp9AdaptiveSourceProjectionContext.class.getSimpleName());

        ArrayNode mapSizes = JsonNodeFactory.instance.arrayNode();
        for (Map.Entry<Long, Vp9PictureMap> entry : vp9PictureMaps.entrySet())
        {
            ObjectNode sizeInfo = JsonNodeFactory.instance.objectNode();
            sizeInfo.put("ssrc", entry.getKey());
            sizeInfo.put("size", entry.getValue().size());
            mapSizes.add(sizeInfo);
        }
        debugState.set("vp9FrameMaps", mapSizes);
        debugState.set("vp9QualityFilter", vp9QualityFilter.getDebugState());

        return debugState;
    }
}
