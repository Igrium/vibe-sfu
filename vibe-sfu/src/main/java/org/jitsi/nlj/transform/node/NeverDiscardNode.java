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
 * A node which will always forward the {@link PacketInfo} it is given.
 * NOTE that the {@link PacketInfo} instance may be modified, but only the original
 * {@link PacketInfo} instance will be forwarded.
 */
public abstract class NeverDiscardNode extends StatsKeepingNode
{
    protected NeverDiscardNode(String name)
    {
        super(name);
    }

    protected abstract void handlePacket(PacketInfo packetInfo);

    @Override
    protected final void doProcessPacket(PacketInfo packetInfo)
    {
        handlePacket(packetInfo);
        doneProcessing(packetInfo);
        next(packetInfo);
    }
}
