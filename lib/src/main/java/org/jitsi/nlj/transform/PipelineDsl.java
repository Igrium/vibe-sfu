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
package org.jitsi.nlj.transform;

import org.jitsi.nlj.transform.node.ConditionalPacketPath;
import org.jitsi.nlj.transform.node.DemuxerNode;
import org.jitsi.nlj.transform.node.Node;

import java.util.function.Consumer;

public class PipelineDsl
{
    public static void packetPath(DemuxerNode demuxerNode, Consumer<ConditionalPacketPath> b)
    {
        ConditionalPacketPath path = new ConditionalPacketPath();
        b.accept(path);
        demuxerNode.addPacketPath(path);
    }

    public static Node pipeline(Consumer<PipelineBuilder> block)
    {
        PipelineBuilder builder = new PipelineBuilder();
        block.accept(builder);
        return builder.build();
    }
}
