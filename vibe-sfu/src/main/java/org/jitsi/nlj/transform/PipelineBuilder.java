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

import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.transform.node.DemuxerNode;
import org.jitsi.nlj.transform.node.ExclusivePathDemuxer;
import org.jitsi.nlj.transform.node.Node;
import org.jitsi.nlj.transform.node.TransformerNode;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

// TODO: look into a marker annotation to prevent inner dsl builders from accidentally setting parent
// member variables when they overlap
public class PipelineBuilder
{
    private Node head;
    private Node tail;

    private void addNode(Node node)
    {
        if (head == null)
        {
            head = node;
        }
        if (tail instanceof DemuxerNode)
        {
            // In the future we could separate 'input/output' nodes from purely
            // input nodes and use that here?
            throw new RuntimeException("Cannot attach node to a DemuxerNode");
        }
        if (tail != null)
        {
            tail.attach(node);
        }
        tail = node;
    }

    public void node(Node node)
    {
        node(node, () -> true);
    }

    public void node(Node node, Supplier<Boolean> condition)
    {
        if (condition.get())
        {
            addNode(node);
        }
    }

    /**
     * simpleNode allows the caller to pass in a block of code which takes a
     * {@link PacketInfo} and returns an output {@link PacketInfo} (or null) to be forwarded to the next
     * {@link Node}
     */
    public void simpleNode(String name, Function<PacketInfo, PacketInfo> packetHandler)
    {
        Node node = new TransformerNode(name)
        {
            {
                this.aggregationKey = this.name;
            }

            @Override
            protected PacketInfo transform(PacketInfo packetInfo)
            {
                return packetHandler.apply(packetInfo);
            }

            @Override
            protected void trace(Runnable f)
            {
                f.run();
            }
        };
        addNode(node);
    }

    public void demux(String name, Consumer<DemuxerNode> block)
    {
        ExclusivePathDemuxer demuxer = new ExclusivePathDemuxer(name);
        block.accept(demuxer);
        addNode(demuxer);
    }

    public Node build()
    {
        return head;
    }
}
