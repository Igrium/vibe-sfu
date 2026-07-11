/*
 * Copyright @ 2019 8x8, Inc
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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.codec.vpx.VpxUtils;
import org.jitsi.nlj.rtp.VideoRtpPacket;
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet;
import org.jitsi.nlj.util.Util;

import java.util.ArrayList;

/**
 * Groups together some RTP/VP9 fields that refer to a specific incoming VP9
 * picture, which may consist of multiple frames (of different spatial layers).
 *
 * Instances of this class are *NOT* thread safe.
 *
 * @author Jonathan Lennox
 */
class Vp9Picture
{
    private final ArrayList<Vp9Frame> frames = new ArrayList<>();

    Vp9Picture(@NotNull Vp9Packet packet, long index)
    {
        int sid = packet.getEffectiveSpatialLayerIndex();

        setFrameAtSid(new Vp9Frame(packet, index), sid);
    }

    @Nullable
    Vp9Frame frame(int sid)
    {
        return sid >= 0 && sid < frames.size() ? frames.get(sid) : null;
    }

    /** The number of spatial-layer slots currently tracked for this picture. */
    int frameCount()
    {
        return frames.size();
    }

    @Nullable
    Vp9Frame frame(@NotNull Vp9Packet packet)
    {
        return frame(packet.getEffectiveSpatialLayerIndex());
    }

    private void setFrameAtSid(@NotNull Vp9Frame frame, int sid)
    {
        Util.setAndExtend(frames, sid, frame, null);
    }

    /**
     * Return the first (lowest-sid, earliest in decoding order) frame that we've received so far.
     * A valid picture must have at least one frame, so this will always return one.
     */
    @NotNull
    private Vp9Frame firstFrame()
    {
        for (Vp9Frame f : frames)
        {
            if (f != null)
            {
                return f;
            }
        }
        throw new IllegalStateException("Picture must have at least one frame");
    }

    /**
     * Return the last (highest-sid, earliest in decoding order) frame that we've received so far.
     * A valid picture must have at least one frame, so this will always return one.
     */
    @NotNull
    private Vp9Frame lastFrame()
    {
        for (int i = frames.size() - 1; i >= 0; i--)
        {
            Vp9Frame f = frames.get(i);
            if (f != null)
            {
                return f;
            }
        }
        throw new IllegalStateException("Picture must have at least one frame");
    }

    long getSsrc()
    {
        return firstFrame().getSsrc();
    }

    long getTimestamp()
    {
        return firstFrame().getTimestamp();
    }

    int getTemporalLayer()
    {
        return firstFrame().getTemporalLayer();
    }

    int getEarliestKnownSequenceNumber()
    {
        return firstFrame().getEarliestKnownSequenceNumber();
    }

    int getLatestKnownSequenceNumber()
    {
        return lastFrame().getLatestKnownSequenceNumber();
    }

    int getPictureId()
    {
        return firstFrame().getPictureId();
    }

    long getIndex()
    {
        return firstFrame().getIndex();
    }

    int getTl0PICIDX()
    {
        return firstFrame().getTl0PICIDX();
    }

    /**
     * Remember another packet of this frame.
     * Note: this assumes every packet is received only once, i.e. a filter
     * like org.jitsi.nlj.transform.node.incoming.PaddingTermination is in use.
     * @param packet The packet to remember. This should be a packet which
     * has tested true with {@link #matchesPicture(Vp9Packet)}.
     */
    @NotNull
    PacketInsertionResult addPacket(@NotNull Vp9Packet packet)
    {
        if (!matchesPicture(packet))
        {
            throw new IllegalArgumentException("Non-matching packet added to picture");
        }

        int sid = packet.getEffectiveSpatialLayerIndex();

        Vp9Frame f = frame(packet);

        if (f != null)
        {
            f.addPacket(packet);
            return new PacketInsertionResult(f, this, false);
        }

        Vp9Frame newF = new Vp9Frame(packet, getIndex());

        setFrameAtSid(newF, sid);

        return new PacketInsertionResult(newF, this, true);
    }

    /**
     * Determines whether the {@link VideoRtpPacket} that is specified as an
     * argument is part of the VP9 picture that is represented by this
     * {@link Vp9Picture} instance.
     */
    private boolean matchesSSRC(@NotNull VideoRtpPacket pkt)
    {
        return getSsrc() == pkt.getSsrc();
    }

    /**
     * Checks whether the specified RTP packet is part of this frame.
     *
     * @param pkt the RTP packet to check whether it's part of this frame.
     * @return true if the specified RTP packet is part of this frame, false
     * otherwise.
     */
    boolean matchesPicture(@NotNull Vp9Packet pkt)
    {
        return matchesSSRC(pkt) && getTimestamp() == pkt.getTimestamp();
    }

    /**
     * Validates that the specified RTP packet consistently matches all the
     * parameters of this picture (or the appropriate frame).
     *
     * This can be useful for diagnosing invalid streams if this fails when
     * {@link #matchesPicture(Vp9Packet)} is true.
     *
     * @param pkt the RTP packet to check whether its parameters match this frame.
     * @throws RuntimeException if the specified RTP packet is inconsistent with this frame
     */
    void validateConsistency(@NotNull Vp9Packet pkt)
    {
        Vp9Frame f = frame(pkt);
        if (f != null)
        {
            f.validateConsistency(pkt);
            return;
        }

        if (getTemporalLayer() == pkt.getTemporalLayerIndex() && getTl0PICIDX() == pkt.getTL0PICIDX() &&
            getPictureId() == pkt.getPictureId())
        {
            /* TODO: also check start, end, seq nums? */
            return;
        }
        StringBuilder s = new StringBuilder()
            .append("Packet ssrc ").append(pkt.getSsrc())
            .append(", seq ").append(pkt.getSequenceNumber())
            .append(", picture id ").append(pkt.getPictureId())
            .append(", timestamp ").append(pkt.getTimestamp())
            .append(" is not consistent with picture ").append(getSsrc())
            .append(", seq ").append(getEarliestKnownSequenceNumber()).append("-")
            .append(getLatestKnownSequenceNumber())
            .append(" picture id ").append(getPictureId())
            .append(", timestamp ").append(getTimestamp())
            .append(": ");

        boolean complained = false;
        if (getTemporalLayer() != pkt.getTemporalLayerIndex())
        {
            s.append("packet temporal layer ").append(pkt.getTemporalLayerIndex())
                .append(" != frame temporal layer ").append(getTemporalLayer());
            complained = true;
        }
        if (getTl0PICIDX() != pkt.getTL0PICIDX())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet TL0PICIDX ").append(pkt.getTL0PICIDX())
                .append(" != frame TL0PICIDX ").append(getTl0PICIDX());
            complained = true;
        }
        if (getPictureId() != pkt.getPictureId())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet PictureID ").append(pkt.getPictureId())
                .append(" != frame PictureID ").append(getPictureId());
        }
        throw new RuntimeException(s.toString());
    }

    /**
     * Check whether this picture is immediately after another one, according
     * to their extended picture IDs.
     */
    boolean isImmediatelyAfter(@NotNull Vp9Picture otherPicture)
    {
        return getPictureId() == VpxUtils.applyExtendedPictureIdDelta(otherPicture.getPictureId(), 1);
    }

    /**
     * The result of calling {@link #addPacket(Vp9Packet)}.
     */
    static class PacketInsertionResult
    {
        /** The frame corresponding to the packet that was inserted. */
        private final Vp9Frame frame;

        /** The picture corresponding to the packet that was inserted. */
        private final Vp9Picture picture;

        /** Whether inserting the packet created a new frame. */
        private final boolean newFrame;

        /** Whether inserting the packet caused a reset. */
        private boolean reset;

        PacketInsertionResult(@NotNull Vp9Frame frame, @NotNull Vp9Picture picture, boolean newFrame)
        {
            this(frame, picture, newFrame, false);
        }

        PacketInsertionResult(@NotNull Vp9Frame frame, @NotNull Vp9Picture picture, boolean newFrame, boolean reset)
        {
            this.frame = frame;
            this.picture = picture;
            this.newFrame = newFrame;
            this.reset = reset;
        }

        @NotNull
        Vp9Frame getFrame()
        {
            return frame;
        }

        @NotNull
        Vp9Picture getPicture()
        {
            return picture;
        }

        boolean isNewFrame()
        {
            return newFrame;
        }

        boolean isReset()
        {
            return reset;
        }

        void setReset(boolean reset)
        {
            this.reset = reset;
        }
    }
}
