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
 * A node which consumes all packets (i.e. does something with them, but does not forward them to another node).
 */
public abstract class ConsumerNode extends StatsKeepingNode
{
    protected ConsumerNode(String name)
    {
        super(name);
    }

    protected abstract void consume(PacketInfo packetInfo);

    @Override
    protected void doProcessPacket(PacketInfo packetInfo)
    {
        consume(packetInfo);
        doneProcessing(packetInfo);
    }

    // Consumer nodes shouldn't have children, because they don't forward
    // any packets anyway.
    @Override
    public Node attach(Node node)
    {
        throw new RuntimeException("ConsumerNode must be a terminal and should not have child nodes attached.");
    }
}
