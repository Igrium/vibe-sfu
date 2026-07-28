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

import org.jitsi.nlj.PacketInfo;
import org.jitsi.utils.queue.PacketQueue;

import java.util.concurrent.ExecutorService;

/**
 * A {@link PacketQueue} of {@link PacketInfo}s, returning buffers when released.
 */
public class PacketInfoQueue extends PacketQueue<PacketInfo>
{
    public PacketInfoQueue(String id, ExecutorService executor, PacketHandler<PacketInfo> handler)
    {
        this(id, executor, handler, 1024);
    }

    public PacketInfoQueue(String id, ExecutorService executor, PacketHandler<PacketInfo> handler, int capacity)
    {
        super(capacity, null, id, handler, executor);
    }

    @Override
    protected void releasePacket(PacketInfo pkt)
    {
        BufferPool.returnBuffer(pkt.getPacket().getBuffer());
    }
}
