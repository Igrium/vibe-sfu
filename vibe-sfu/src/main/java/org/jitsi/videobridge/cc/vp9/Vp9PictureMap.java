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
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet;
import org.jitsi.nlj.util.ArrayCache;
import org.jitsi.nlj.util.PictureIdIndexTracker;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.util.function.Predicate;

import static java.lang.Long.max;
import static java.lang.Long.min;

/**
 * A history of recent pictures on a VP9 stream.
 */
public class Vp9PictureMap
{
    static final int PICTURE_MAP_SIZE = 500; /* Matches PacketCache default size. */

    /** Cache mapping picture IDs to pictures. */
    private final PictureHistory pictureHistory = new PictureHistory(PICTURE_MAP_SIZE);

    private final Logger logger;

    public Vp9PictureMap(@NotNull Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(Vp9PictureMap.class.getName());
    }

    /** Find a picture in the picture map, based on a packet. */
    public synchronized Vp9Picture findPicture(@NotNull Vp9Packet packet)
    {
        return pictureHistory.get(packet.getPictureId());
    }

    /** Get the current size of the map. */
    public int size()
    {
        return pictureHistory.numCached;
    }

    /** Check whether this is a large jump from previous state, so the map should be reset. */
    private boolean isLargeJump(@NotNull Vp9Packet packet)
    {
        Vp9Picture latestPicture = pictureHistory.getLatestPicture();
        if (latestPicture == null)
        {
            return false;
        }

        int picDelta = VpxUtils.getExtendedPictureIdDelta(packet.getPictureId(), latestPicture.getPictureId());
        if (picDelta > PICTURE_MAP_SIZE)
        {
            return true;
        }

        long tsDelta = RtpUtils.getTimestampDiff(packet.getTimestamp(), latestPicture.getTimestamp());

        if (picDelta < 0)
        {
            /* if picDelta is negative but timestamp or sequence delta is positive, we've cycled. */
            if (tsDelta > 0)
            {
                return true;
            }
            if (RtpUtils.getSequenceNumberDelta(
                packet.getSequenceNumber(), latestPicture.getLatestKnownSequenceNumber()) > 0)
            {
                return true;
            }
        }

        /* If tsDelta is more than twice the picture map size at 1 fps, we've cycled. */
        return tsDelta > PICTURE_MAP_SIZE * 90000L * 2;
    }

    /** Insert a packet into the picture map. Return a PacketInsertionResult
     *  describing what happened.
     * @param packet The packet to insert.
     * @return What happened. null if insertion failed.
     */
    @Nullable
    public synchronized Vp9Picture.PacketInsertionResult insertPacket(@NotNull Vp9Packet packet)
    {
        int pictureId = packet.getPictureId();

        if (pictureId == -1)
        {
            /* Picture map indexes by picture ID. All supported browsers should currently be setting it. */
            /* Log message will have been logged by Vp9Parser in jmt. */
            return null;
        }

        if (isLargeJump(packet))
        {
            pictureHistory.indexTracker.resetAt(pictureId);
            Vp9Picture picture = pictureHistory.insert(packet);
            if (picture == null)
            {
                return null;
            }

            return new Vp9Picture.PacketInsertionResult(picture.frame(packet), picture, true, true);
        }

        Vp9Picture picture = pictureHistory.get(pictureId);
        if (picture != null)
        {
            if (!picture.matchesPicture(packet))
            {
                if (picture.getPictureId() != pictureId)
                {
                    throw new IllegalStateException("Picture map returned picture with picture ID " +
                        picture.getPictureId() +
                        " when asked for picture with picture ID " + pictureId);
                }
                logger.warn("Cannot insert packet in picture map: " +
                    "picture with ssrc " + picture.getSsrc() +
                    ", timestamp " + picture.getTimestamp() +
                    ", and sequence number range " + picture.getEarliestKnownSequenceNumber() +
                    "-" + picture.getLatestKnownSequenceNumber() +
                    ", and packet " + packet.getSequenceNumber() +
                    " with ssrc " + packet.getSsrc() +
                    ", timestamp " + packet.getTimestamp() +
                    ", and sequence number " + packet.getSequenceNumber() +
                    " both have picture ID " + pictureId);
                return null;
            }
            try
            {
                picture.validateConsistency(packet);
            }
            catch (Exception e)
            {
                logger.warn(e);
            }

            return picture.addPacket(packet);
        }

        Vp9Picture newPicture = pictureHistory.insert(packet);
        if (newPicture == null)
        {
            return null;
        }

        return new Vp9Picture.PacketInsertionResult(newPicture.frame(packet), newPicture, true);
    }

    @Nullable
    public synchronized Vp9Frame nextFrame(@NotNull Vp9Frame frame)
    {
        return pictureHistory.findAfter(frame, (Vp9Frame f) -> true);
    }

    @Nullable
    public synchronized Vp9Frame nextFrameWith(@NotNull Vp9Frame frame, Predicate<Vp9Frame> pred)
    {
        return pictureHistory.findAfter(frame, pred);
    }

    @Nullable
    public synchronized Vp9Frame prevFrame(@NotNull Vp9Frame frame)
    {
        return pictureHistory.findBefore(frame, (Vp9Frame f) -> true);
    }

    @Nullable
    public synchronized Vp9Frame prevFrameWith(@NotNull Vp9Frame frame, Predicate<Vp9Frame> pred)
    {
        return pictureHistory.findBefore(frame, pred);
    }

    @Nullable
    public Vp9Frame findPrevAcceptedFrame(@NotNull Vp9Frame frame)
    {
        return prevFrameWith(frame, Vp9Frame::isAccepted);
    }

    @Nullable
    public Vp9Frame findNextAcceptedFrame(@NotNull Vp9Frame frame)
    {
        return nextFrameWith(frame, Vp9Frame::isAccepted);
    }

    @Nullable
    public Vp9Frame findNextBaseTl0(@NotNull Vp9Frame frame)
    {
        return nextFrameWith(frame, (Vp9Frame f) -> f.getSpatialLayer() <= 0 && f.getTemporalLayer() <= 0);
    }

    private static class PictureHistory extends ArrayCache<Vp9Picture>
    {
        PictureHistory(int size)
        {
            super(size, (k) -> k, false, Clock.systemUTC());
        }

        int numCached = 0;
        long firstIndex = -1;

        final PictureIdIndexTracker indexTracker = new PictureIdIndexTracker();

        /**
         * Gets a picture with a given VP9 picture ID from the cache.
         */
        Vp9Picture get(int pictureId)
        {
            long index = indexTracker.interpret(pictureId);
            return getIndex(index);
        }

        /**
         * Gets a picture with a given VP9 picture ID index from the cache.
         */
        private Vp9Picture getIndex(long index)
        {
            if (index <= getLastIndex() - getSize())
            {
                /* We don't want to remember old pictures even if they're still
                   tracked; their neighboring pictures may have been evicted,
                   so findBefore / findAfter will return bogus data. */
                return null;
            }
            ArrayCache.Container<Vp9Picture> c = getContainer(index);
            if (c == null)
            {
                return null;
            }
            return c.item;
        }

        /** Get the latest picture in the tracker. */
        Vp9Picture getLatestPicture()
        {
            return getIndex(getLastIndex());
        }

        /** Insert a new picture for the given Vp9 packet. */
        Vp9Picture insert(@NotNull Vp9Packet packet)
        {
            int pictureId = packet.getPictureId();
            long index = indexTracker.update(pictureId);
            Vp9Picture picture = new Vp9Picture(packet, index);
            boolean inserted = super.insertItem(picture, index);
            if (inserted)
            {
                numCached++;
                if (firstIndex == -1 || index < firstIndex)
                {
                    firstIndex = index;
                }
                return picture;
            }
            return null;
        }

        /**
         * Called when an item in the cache is replaced/discarded.
         */
        @Override
        protected void discardItem(Vp9Picture item)
        {
            numCached--;
        }

        @Nullable
        Vp9Frame findBefore(@NotNull Vp9Frame frame, Predicate<Vp9Frame> pred)
        {
            long lastIndex = getLastIndex();
            if (lastIndex == -1)
            {
                return null;
            }
            long index = indexTracker.interpret(frame.getPictureId());
            long searchStartIndex = min(index, lastIndex);
            long searchEndIndex = max(lastIndex - getSize(), firstIndex - 1);
            return doFind(pred, searchStartIndex, searchEndIndex, frame.getEffectiveSpatialLayer() - 1, -1);
        }

        @Nullable
        Vp9Frame findAfter(@NotNull Vp9Frame frame, Predicate<Vp9Frame> pred)
        {
            long lastIndex = getLastIndex();
            if (lastIndex == -1)
            {
                return null;
            }
            long index = indexTracker.interpret(frame.getPictureId());
            if (index > lastIndex)
            {
                return null;
            }
            long searchStartIndex = max(index, max(lastIndex - getSize() + 1, firstIndex));
            return doFind(pred, searchStartIndex, lastIndex + 1, frame.getEffectiveSpatialLayer() + 1, 1);
        }

        @Nullable
        private Vp9Frame doFind(Predicate<Vp9Frame> pred, long startIndex, long endIndex, int startLayer,
            int increment)
        {
            if (!((increment > 0 && startIndex <= endIndex) || (increment < 0 && startIndex >= endIndex)))
            {
                throw new IllegalArgumentException("Values of startIndex=" + startIndex + ", endIndex=" + endIndex +
                    ", and increment=" + increment + " could lead to infinite loop");
            }

            long index = startIndex;
            int layer = startLayer;
            boolean firstPicture = true;
            while (index != endIndex)
            {
                Vp9Picture picture = getIndex(index);
                if (picture != null)
                {
                    if (!firstPicture)
                    {
                        layer = increment < 0 ? picture.frameCount() - 1 : 0;
                    }
                    while (layer >= 0 && layer < picture.frameCount())
                    {
                        Vp9Frame frame = picture.frame(layer);
                        if (frame != null && pred.test(frame))
                        {
                            return frame;
                        }
                        layer += increment;
                    }
                }
                index += increment;
                firstPicture = false;
            }
            return null;
        }
    }
}
