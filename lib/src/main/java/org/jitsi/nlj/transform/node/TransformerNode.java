/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.transform.node;

import org.jitsi.nlj.PacketInfo;

/**
 * A {@link Node} which transforms a single packet, possibly dropping it (by returning null).
 * If null is returned, the {@link PacketInfo} instance given to {@link #transform(PacketInfo)} will be
 * discarded.
 */
public abstract class TransformerNode extends StatsKeepingNode
{
    protected TransformerNode(String name)
    {
        super(name);
    }

    protected abstract PacketInfo transform(PacketInfo packetInfo);

    @Override
    protected void doProcessPacket(PacketInfo packetInfo)
    {
        PacketInfo transformedPacket = transform(packetInfo);
        doneProcessing(transformedPacket);
        if (transformedPacket == null)
        {
            super.packetDiscarded(packetInfo);
        }
        else
        {
            next(transformedPacket);
        }
    }

    @Override
    protected final void packetDiscarded(PacketInfo packetInfo)
    {
        throw new RuntimeException(
            "No subclass of TransformerNode should call packetDiscarded, return null from 'transform' instead"
        );
    }
}
