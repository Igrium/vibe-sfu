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

import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.NodeVisitor;
import org.jitsi.rtp.Packet;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public abstract class DemuxerNode extends StatsKeepingNode
{
    protected Set<ConditionalPacketPath> transformPaths = new CopyOnWriteArraySet<>();

    protected DemuxerNode(String name)
    {
        super(name + " demuxer");
    }

    public DemuxerNode addPacketPath(ConditionalPacketPath packetPath)
    {
        transformPaths.add(packetPath);
        // We want to make sure the paths correctly see this Demuxer in their 'inputNodes' so that we can traverse the
        // reverse tree correctly.
        packetPath.getPath().addParent(this);

        return this;
    }

    public DemuxerNode addPacketPath(String name, Predicate<Packet> predicate, Node root)
    {
        ConditionalPacketPath path = new ConditionalPacketPath(name);
        path.setPredicate(predicate);
        path.setPath(root);

        return addPacketPath(path);
    }

    public void removePacketPaths()
    {
        for (ConditionalPacketPath path : transformPaths)
        {
            path.getPath().removeParent(this);
        }
        transformPaths.clear();
    }

    @Override
    public Node attach(Node node)
    {
        throw new RuntimeException();
    }

    @Override
    public void detachNext()
    {
        throw new RuntimeException();
    }

    @Override
    public void visit(NodeVisitor visitor)
    {
        visitor.visit(this);
        for (ConditionalPacketPath conditionalPath : transformPaths)
        {
            conditionalPath.getPath().visit(visitor);
        }
    }

    @Override
    public Collection<Node> getChildren()
    {
        return transformPaths.stream().map(ConditionalPacketPath::getPath).collect(Collectors.toList());
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock superStats = super.getNodeStats();

        for (ConditionalPacketPath path : transformPaths)
        {
            superStats.addNumber("packets_accepted_" + path.getName(), path.packetsAccepted);
        }
        return superStats;
    }

    @Override
    public com.fasterxml.jackson.databind.node.ObjectNode statsJson()
    {
        com.fasterxml.jackson.databind.node.ObjectNode o = super.statsJson();
        for (ConditionalPacketPath path : transformPaths)
        {
            o.put("packets_accepted_" + path.getName(), path.packetsAccepted);
        }
        return o;
    }
}
