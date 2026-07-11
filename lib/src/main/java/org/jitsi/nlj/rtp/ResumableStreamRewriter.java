/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp;

import org.jitsi.nlj.util.ArrayCache;
import org.jitsi.nlj.util.RtpSequenceIndexTracker;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.util.RtpUtils;

/**
 * Rewrites sequence numbers for RTP streams by hiding any gaps caused by
 * dropped packets. Rewriters are not thread-safe. If multiple threads access a
 * rewriter concurrently, it must be synchronized externally.
 *
 * Port of the class in libjitsi.
 *
 * @author Maryam Daneshi
 * @author George Politis
 * @author Boris Grozev
 * @author Jonathan Lennox
 */
public class ResumableStreamRewriter
{
    private final boolean keepHistory;

    /**
     * The sequence number delta between what's been accepted and what's been
     * received, mod 2^16.
     */
    private int seqnumDelta = 0;

    private StreamRewriteHistory history;

    /**
     * The highest sequence number that got accepted, mod 2^16.
     */
    private int highestSequenceNumberSent = -1;

    public ResumableStreamRewriter()
    {
        this(false);
    }

    public ResumableStreamRewriter(boolean keepHistory)
    {
        this.keepHistory = keepHistory;
    }

    public boolean isKeepHistory()
    {
        return keepHistory;
    }

    public int getSeqnumDelta()
    {
        return seqnumDelta;
    }

    public int getHighestSequenceNumberSent()
    {
        return highestSequenceNumberSent;
    }

    /**
     * Rewrites the sequence number of the given RTP packet hiding any gaps caused by drops.
     *
     * @param accept true if the packet is accepted, false otherwise
     */
    public void rewriteRtp(boolean accept, RtpPacket rtpPacket)
    {
        int sequenceNumber = rtpPacket.getSequenceNumber();
        int newSequenceNumber = rewriteSequenceNumber(accept, sequenceNumber);
        if (sequenceNumber != newSequenceNumber)
        {
            rtpPacket.setSequenceNumber(newSequenceNumber);
        }
    }

    /**
     * Rewrites the sequence number passed as a parameter, hiding any gaps
     * caused by drops.
     *
     * @param accept true if the packet is accepted, false otherwise
     * @param sequenceNumber the sequence number to rewrite
     * @return a rewritten sequence number that hides any gaps caused by drops.
     */
    public int rewriteSequenceNumber(boolean accept, int sequenceNumber)
    {
        if (keepHistory && !accept && history == null)
        {
            /* Don't instantiate history until it's needed; many streams never discard. */
            history = new StreamRewriteHistory(highestSequenceNumberSent);
        }

        if (history != null)
        {
            return history.rewriteSequenceNumber(accept, sequenceNumber);
        }

        if (accept)
        {
            // overwrite the sequence number (if needed)
            int newSequenceNumber = (sequenceNumber - seqnumDelta) & 0xffff;

            // init or update the highest sent sequence number (if needed)
            if (highestSequenceNumberSent == -1 || RtpUtils.isNewerThan(newSequenceNumber, highestSequenceNumberSent))
            {
                highestSequenceNumberSent = newSequenceNumber;
            }

            return newSequenceNumber;
        }
        else
        {
            // update the sequence number delta (if needed)
            if (highestSequenceNumberSent != -1)
            {
                int newDelta = (sequenceNumber - highestSequenceNumberSent) & 0xffff;

                if (RtpUtils.isNewerThan(newDelta, seqnumDelta))
                {
                    seqnumDelta = newDelta;
                }
            }

            return sequenceNumber;
        }
    }

    public int getGapsLeft()
    {
        return history != null ? history.gapsLeft : 0;
    }

    private static class RewriteHistoryItem
    {
        Boolean accept;
        final long newIndex;

        RewriteHistoryItem(Boolean accept, long newIndex)
        {
            this.accept = accept;
            this.newIndex = newIndex;
        }
    }

    private static class StreamRewriteHistory extends ArrayCache<RewriteHistoryItem>
    {
        /**
         * The maximum number of packets to save history for.
         *
         * NOTE rtt + minimum amount
         * XXX this is an uninformed value.
         */
        private static final int MAX_REWRITE_HISTORY = 1000;

        /** Map an index back to a sequence number. */
        private static int toSequenceNumber(long index)
        {
            return (int) (index & 0xffff);
        }

        private long firstIndex = -1L;

        private int gapsLeft = 0;

        private final RtpSequenceIndexTracker rtpSequenceIndexTracker = new RtpSequenceIndexTracker();

        StreamRewriteHistory(int highestSeqSent)
        {
            // (Deviation: upstream's ArrayCache clone function for this cache is the identity function, since
            // it doesn't want to clone objects that get put in the tracker; and it disables synchronization since
            // the caller is expected to have this object synchronized already.)
            super(MAX_REWRITE_HISTORY, item -> item, false);
            if (highestSeqSent != -1)
            {
                rewriteSequenceNumber(true, highestSeqSent);
            }
        }

        private void fillBetween(long start, long end, long firstNewIndex)
        {
            if (end <= getLastIndex() - getSize() + 1)
            {
                return;
            }
            long actualStart = start <= getLastIndex() - getSize() ? getLastIndex() - getSize() + 1 : start;

            long newIndex = firstNewIndex;

            for (long i = actualStart; i <= end; i++)
            {
                insertItem(new RewriteHistoryItem(null, newIndex), i);
                newIndex++;
            }
        }

        int rewriteSequenceNumber(boolean accept, int sequenceNumber)
        {
            long index = rtpSequenceIndexTracker.update(sequenceNumber);

            long newIndex;

            if (firstIndex == -1L)
            {
                /* First index seen. */
                insertItem(new RewriteHistoryItem(accept, index), index);

                firstIndex = index;

                newIndex = index;
            }
            else if (index > getLastIndex())
            {
                /* New.  Roll forward, filling in gap if necessary. */

                long newestIndex = getLastIndex();

                Container<RewriteHistoryItem> newest = getContainer(newestIndex);
                if (newest == null)
                {
                    throw new IllegalStateException("No newest container found");
                }

                long newestNewIndex = newest.item.newIndex;

                long indexGap = index - newestIndex;

                long newGap = indexGap - (accept ? 0 : 1);

                newIndex = newestNewIndex + newGap;

                insertItem(new RewriteHistoryItem(accept, newIndex), index);

                fillBetween(newestIndex + 1, index - 1, newestNewIndex + 1);
            }
            else if (index > getLastIndex() - getSize() && index >= firstIndex)
            {
                /* In history.  Retrieve. */

                Container<RewriteHistoryItem> container = getContainer(index);
                if (container == null)
                {
                    throw new IllegalStateException("No container found for index " + index + " in history");
                }
                RewriteHistoryItem item = container.item;

                if (item.accept == null)
                {
                    item.accept = accept;
                    if (!accept)
                    {
                        gapsLeft++;
                    }
                }

                newIndex = item.newIndex;
            }
            else if (index > getLastIndex() - getSize() && index < firstIndex)
            {
                /* Older than previous oldest, but still in range of the map.
                   Project map backwards. */

                long oldestIndex = Math.max(getLastIndex() - getSize() + 1, firstIndex);
                Container<RewriteHistoryItem> oldest = getContainer(oldestIndex);
                if (oldest == null)
                {
                    throw new IllegalStateException("No oldest container found");
                }

                long oldestNewIndex = oldest.item.newIndex;

                long indexGap = index - oldestIndex; // Negative

                long newGap = indexGap + (accept ? 0 : 1);

                newIndex = oldestNewIndex + newGap;

                insertItem(new RewriteHistoryItem(accept, newIndex), index);

                fillBetween(index + 1, oldestIndex - 1, newIndex + 1);

                firstIndex = index;
            }
            else
            {
                /* Older than the map. */

                long oldestIndex = Math.max(getLastIndex() - getSize() + 1, firstIndex);
                Container<RewriteHistoryItem> oldest = getContainer(oldestIndex);
                if (oldest == null)
                {
                    throw new IllegalStateException("No oldest container found");
                }
                long oldestDelta = oldestIndex - oldest.item.newIndex;

                newIndex = index - oldestDelta;
            }

            if (accept)
            {
                return toSequenceNumber(newIndex);
            }
            /* Don't care about sequence numbers for non-accepted packets,
             so make sure rewriteRtp does nothing. */
            return sequenceNumber;
        }
    }
}
