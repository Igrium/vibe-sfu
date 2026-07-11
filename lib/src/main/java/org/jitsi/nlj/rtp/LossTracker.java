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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.stats.RateTracker;

public class LossTracker implements LossListener
{
    private final RateTracker lostPackets = new RateTracker(DurationKt.getSecs(60), DurationKt.getSecs(1));
    private final RateTracker receivedPackets = new RateTracker(DurationKt.getSecs(60), DurationKt.getSecs(1));

    @Override
    public synchronized void packetReceived(boolean previouslyReportedLost)
    {
        receivedPackets.update(1);
        if (previouslyReportedLost)
        {
            lostPackets.update(-1);
        }
    }

    @Override
    public synchronized void packetLost(int numLost)
    {
        lostPackets.update(numLost);
    }

    public synchronized Snapshot getSnapshot()
    {
        return new Snapshot(
            lostPackets.getAccumulatedCount(),
            receivedPackets.getAccumulatedCount()
        );
    }

    public static class Snapshot
    {
        public final long packetsLost;
        public final long packetsReceived;

        public Snapshot(long packetsLost, long packetsReceived)
        {
            this.packetsLost = packetsLost;
            this.packetsReceived = packetsReceived;
        }

        public ObjectNode toJson()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("packets_lost", packetsLost);
            node.put("packets_received", packetsReceived);
            return node;
        }
    }
}
