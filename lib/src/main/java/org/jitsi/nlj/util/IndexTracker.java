/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.util;

/**
 * Index tracker inspired by the RFC3711 RTP sequence number index tracker.
 *
 * (Deviation: upstream is a Kotlin {@code sealed class}; ported as a plain {@code abstract class} since this
 * package has no other subclasses that need file-level restriction, and Java's {@code sealed} keyword would only
 * add ceremony here.)
 */
public abstract class IndexTracker<T>
{
    private long roc = 0L;

    private T highestSeqNumReceived = null;

    abstract long addRollover(T seqNum, long roc);
    abstract boolean rollsOver(T a, T b);
    abstract boolean isOlderThan(T a, T b);
    abstract long toLong(T t);
    abstract boolean isValid(T t);

    private boolean isNewerThan(T a, T b)
    {
        return !isOlderThan(a, b) && !a.equals(b);
    }

    private T validate(T t)
    {
        if (!isValid(t))
        {
            throw new IllegalArgumentException("Invalid sequence number: " + t);
        }
        return t;
    }

    /**
     * return the index (as defined by RFC3711 at https://tools.ietf.org/html/rfc3711#section-3.3.1)
     * for the given seqNum, updating our ROC if we roll over.
     * NOTE that this method must be called for all 'received' sequence numbers so that it may keep
     * its rollover counter accurate
     */
    public long update(T seqNum)
    {
        return getIndex(validate(seqNum), true);
    }

    /**
     * Interprets an RTP sequence number in the context of the highest sequence number received. Returns the index
     * which corresponds to the packet, but does not update the ROC.
     */
    public long interpret(T seqNum)
    {
        return getIndex(validate(seqNum), false);
    }

    /**
     * return the index (as defined by RFC3711 at https://tools.ietf.org/html/rfc3711#section-3.3.1)
     * for the given {@code seqNum}. If {@code updateRoc} is {@code true} and we've rolled over, updates our ROC.
     */
    private long getIndex(T seqNum, boolean updateRoc)
    {
        T highestSeqNumReceived = this.highestSeqNumReceived;
        if (highestSeqNumReceived == null)
        {
            if (updateRoc)
            {
                this.highestSeqNumReceived = seqNum;
            }
            return toLong(seqNum);
        }

        long v;
        if (rollsOver(seqNum, highestSeqNumReceived))
        {
            // Seq num was from the previous roc value
            v = roc - 1;
        }
        else if (rollsOver(highestSeqNumReceived, seqNum))
        {
            // We've rolled over, so update the roc in place if updateRoc is set, otherwise return the right value
            // (our current roc + 1)
            if (updateRoc)
            {
                v = ++roc;
            }
            else
            {
                v = roc + 1;
            }
        }
        else
        {
            v = roc;
        }

        if (updateRoc && isNewerThan(seqNum, highestSeqNumReceived))
        {
            this.highestSeqNumReceived = seqNum;
        }

        return addRollover(seqNum, v);
    }

    /** Force this sequence number to be interpreted as the new highest, regardless of its rollover state. */
    public void resetAt(T seq)
    {
        validate(seq);
        T highestSeqNumReceived = this.highestSeqNumReceived;
        if (highestSeqNumReceived == null || isOlderThan(seq, highestSeqNumReceived))
        {
            roc++;
            this.highestSeqNumReceived = seq;
        }
        getIndex(seq, true);
    }

    public String debugState()
    {
        return "{roc=" + roc + ", highestSeqNumReceived=" + highestSeqNumReceived + "}";
    }
}
