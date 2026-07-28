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
package org.jitsi.videobridge.cc.av1;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket;
import org.jitsi.nlj.util.ArrayCache;
import org.jitsi.nlj.util.RtpSequenceIndexTracker;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.util.function.Predicate;

import static java.lang.Long.max;
import static java.lang.Long.min;

/**
 * A history of recent frames on a Av1 stream.
 */
public class Av1DDFrameMap
{
    static final int FRAME_MAP_SIZE = 500; /* Matches PacketCache default size. */

    /** Cache mapping frame IDs to frames. */
    private final FrameHistory frameHistory = new FrameHistory(FRAME_MAP_SIZE);

    private final Logger logger;

    public Av1DDFrameMap(@NotNull Logger parentLogger)
    {
        this.logger = parentLogger.createChildLogger(Av1DDFrameMap.class.getName());
    }

    /** Find a frame in the frame map, based on a packet. */
    public synchronized Av1DDFrame findFrame(@NotNull Av1DDPacket packet)
    {
        return frameHistory.get(packet.getFrameNumber());
    }

    /** Get the current size of the map. */
    public int size()
    {
        return frameHistory.numCached;
    }

    /** Check whether this is a large jump from previous state, so the map should be reset. */
    private boolean isLargeJump(@NotNull Av1DDPacket packet)
    {
        Av1DDFrame latestFrame = frameHistory.getLatestFrame();
        if (latestFrame == null)
        {
            return false;
        }

        int picDelta = RtpUtils.getSequenceNumberDelta(packet.getFrameNumber(), latestFrame.getFrameNumber());
        if (picDelta > FRAME_MAP_SIZE)
        {
            return true;
        }

        long tsDelta = RtpUtils.getTimestampDiff(packet.getTimestamp(), latestFrame.getTimestamp());

        if (picDelta < 0)
        {
            /* if picDelta is negative but timestamp or sequence delta is positive, we've cycled. */
            if (tsDelta > 0)
            {
                return true;
            }
            if (RtpUtils.isNewerThan(packet.getSequenceNumber(), latestFrame.getLatestKnownSequenceNumber()))
            {
                return true;
            }
        }

        /* If tsDelta is more than twice the frame map size at 1 fps, we've cycled. */
        return tsDelta > FRAME_MAP_SIZE * 90000L * 2;
    }

    /** Insert a packet into the frame map. Return a PacketInsertionResult
     *  describing what happened.
     * @param packet The packet to insert.
     * @return What happened. null if insertion failed.
     */
    @Nullable
    public synchronized PacketInsertionResult insertPacket(@NotNull Av1DDPacket packet)
    {
        int frameNumber = packet.getFrameNumber();

        if (isLargeJump(packet))
        {
            frameHistory.indexTracker.resetAt(frameNumber);
            Av1DDFrame frame = frameHistory.insert(packet);
            if (frame == null)
            {
                return null;
            }

            return new PacketInsertionResult(frame, true, true);
        }

        Av1DDFrame frame = frameHistory.get(frameNumber);
        if (frame != null)
        {
            if (!frame.matchesFrame(packet))
            {
                if (frame.getFrameNumber() != frameNumber)
                {
                    throw new IllegalStateException("frame map returned frame with frame number " +
                        frame.getFrameNumber() +
                        " when asked for frame with frame ID " + frameNumber);
                }
                logger.warn("Cannot insert packet in frame map: " +
                    "frame with ssrc " + frame.getSsrc() +
                    ", timestamp " + frame.getTimestamp() +
                    ", and sequence number range " + frame.getEarliestKnownSequenceNumber() +
                    "-" + frame.getLatestKnownSequenceNumber() +
                    ", and packet " + packet.getSequenceNumber() +
                    " with ssrc " + packet.getSsrc() +
                    ", timestamp " + packet.getTimestamp() +
                    ", and sequence number " + packet.getSequenceNumber() +
                    " both have frame ID " + frameNumber);
                return null;
            }
            try
            {
                frame.validateConsistency(packet);
            }
            catch (Exception e)
            {
                logger.warn(e);
            }

            frame.addPacket(packet);
            return new PacketInsertionResult(frame, false, false);
        }

        Av1DDFrame newFrame = frameHistory.insert(packet);
        if (newFrame == null)
        {
            return null;
        }

        return new PacketInsertionResult(newFrame, true, false);
    }

    /** Insert a frame. Only used for unit testing. */
    synchronized void insertFrame(@NotNull Av1DDFrame frame)
    {
        frameHistory.insert(frame);
    }

    public synchronized Av1DDFrame getIndex(long frameIndex)
    {
        return frameHistory.getIndex(frameIndex);
    }

    @Nullable
    public synchronized Av1DDFrame nextFrame(@NotNull Av1DDFrame frame)
    {
        return frameHistory.findAfter(frame, (Av1DDFrame f) -> true);
    }

    @Nullable
    public synchronized Av1DDFrame nextFrameWith(@NotNull Av1DDFrame frame, Predicate<Av1DDFrame> pred)
    {
        return frameHistory.findAfter(frame, pred);
    }

    @Nullable
    public synchronized Av1DDFrame prevFrame(@NotNull Av1DDFrame frame)
    {
        return frameHistory.findBefore(frame, (Av1DDFrame f) -> true);
    }

    @Nullable
    public synchronized Av1DDFrame prevFrameWith(@NotNull Av1DDFrame frame, Predicate<Av1DDFrame> pred)
    {
        return frameHistory.findBefore(frame, pred);
    }

    @Nullable
    public Av1DDFrame findPrevAcceptedFrame(@NotNull Av1DDFrame frame)
    {
        return prevFrameWith(frame, Av1DDFrame::isAccepted);
    }

    @Nullable
    public Av1DDFrame findNextAcceptedFrame(@NotNull Av1DDFrame frame)
    {
        return nextFrameWith(frame, Av1DDFrame::isAccepted);
    }

    /**
     * The result of calling {@link #insertPacket(Av1DDPacket)}.
     */
    public static class PacketInsertionResult
    {
        /** The frame corresponding to the packet that was inserted. */
        private final Av1DDFrame frame;

        /** Whether inserting the packet created a new frame. */
        private final boolean newFrame;

        /** Whether inserting the packet caused a reset. */
        private final boolean reset;

        PacketInsertionResult(@NotNull Av1DDFrame frame, boolean newFrame, boolean reset)
        {
            this.frame = frame;
            this.newFrame = newFrame;
            this.reset = reset;
        }

        @NotNull
        public Av1DDFrame getFrame()
        {
            return frame;
        }

        public boolean isNewFrame()
        {
            return newFrame;
        }

        public boolean isReset()
        {
            return reset;
        }
    }

    private static class FrameHistory extends ArrayCache<Av1DDFrame>
    {
        FrameHistory(int size)
        {
            super(size, (k) -> k, false, Clock.systemUTC());
        }

        int numCached = 0;
        long firstIndex = -1;

        final RtpSequenceIndexTracker indexTracker = new RtpSequenceIndexTracker();

        /**
         * Gets a frame with a given frame number from the cache.
         */
        Av1DDFrame get(int frameNumber)
        {
            long index = indexTracker.interpret(frameNumber);
            return getIndex(index);
        }

        /**
         * Gets a frame with a given frame number index from the cache.
         */
        Av1DDFrame getIndex(long index)
        {
            if (index <= getLastIndex() - getSize())
            {
                /* We don't want to remember old frames even if they're still
                   tracked; their neighboring frames may have been evicted,
                   so findBefore / findAfter will return bogus data. */
                return null;
            }
            ArrayCache.Container<Av1DDFrame> c = getContainer(index);
            if (c == null)
            {
                return null;
            }
            return c.item;
        }

        /** Get the latest frame in the tracker. */
        Av1DDFrame getLatestFrame()
        {
            return getIndex(getLastIndex());
        }

        boolean insert(@NotNull Av1DDFrame frame)
        {
            boolean ret = super.insertItem(frame, frame.getIndex());
            if (ret)
            {
                numCached++;
                if (firstIndex == -1 || frame.getIndex() < firstIndex)
                {
                    firstIndex = frame.getIndex();
                }
            }
            return ret;
        }

        @Nullable
        Av1DDFrame insert(@NotNull Av1DDPacket packet)
        {
            long index = indexTracker.update(packet.getFrameNumber());
            Av1DDFrame frame = new Av1DDFrame(packet, index);
            return insert(frame) ? frame : null;
        }

        /**
         * Called when an item in the cache is replaced/discarded.
         */
        @Override
        protected void discardItem(Av1DDFrame item)
        {
            numCached--;
        }

        @Nullable
        Av1DDFrame findBefore(@NotNull Av1DDFrame frame, Predicate<Av1DDFrame> pred)
        {
            long lastIndex = getLastIndex();
            if (lastIndex == -1)
            {
                return null;
            }
            long index = frame.getIndex();
            long searchStartIndex = min(index - 1, lastIndex);
            long searchEndIndex = max(lastIndex - getSize(), firstIndex - 1);
            return doFind(pred, searchStartIndex, searchEndIndex, -1);
        }

        @Nullable
        Av1DDFrame findAfter(@NotNull Av1DDFrame frame, Predicate<Av1DDFrame> pred)
        {
            long lastIndex = getLastIndex();
            if (lastIndex == -1)
            {
                return null;
            }
            long index = frame.getIndex();
            if (index >= lastIndex)
            {
                return null;
            }
            long searchStartIndex = max(index + 1, max(lastIndex - getSize() + 1, firstIndex));
            return doFind(pred, searchStartIndex, lastIndex + 1, 1);
        }

        @Nullable
        private Av1DDFrame doFind(Predicate<Av1DDFrame> pred, long startIndex, long endIndex, int increment)
        {
            if (!((increment > 0 && startIndex <= endIndex) || (increment < 0 && startIndex >= endIndex)))
            {
                throw new IllegalArgumentException("Values of startIndex=" + startIndex + ", endIndex=" + endIndex +
                    ", and increment=" + increment + " could lead to infinite loop");
            }

            for (long index = startIndex; index != endIndex; index += increment)
            {
                Av1DDFrame frame = getIndex(index);
                if (frame != null && pred.test(frame))
                {
                    return frame;
                }
            }
            return null;
        }
    }
}
