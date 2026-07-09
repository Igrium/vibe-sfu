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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.Event;
import org.jitsi.nlj.EventHandler;
import org.jitsi.nlj.PacketHandler;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.Stoppable;
import org.jitsi.nlj.transform.NodeStatsProducer;
import org.jitsi.nlj.transform.NodeVisitor;
import org.jitsi.nlj.transform.node.debug.PayloadVerificationPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An abstract base class for all {@link Node} subclasses.  This class
 * takes care of the following behaviors:
 * 1) Attaching the next node in the chain
 * 2) Adding and removing parent nodes
 * 3) Propagating {@code visit} calls
 */
public abstract class Node implements PacketHandler, EventHandler, NodeStatsProducer, Stoppable
{
    public static boolean TRACE_ENABLED = false;

    public static boolean PLUGINS_ENABLED = false;

    // 'Plugins' are observers which, when enabled, will be passed every packet that passes through
    // every node
    public static final Set<NodePlugin> plugins = new HashSet<>();

    public static void enablePayloadVerification(boolean enable)
    {
        if (enable)
        {
            PLUGINS_ENABLED = true;
            plugins.add(PayloadVerificationPlugin.INSTANCE);
            PacketInfo.enablePayloadVerification = true;
        }
        else
        {
            plugins.remove(PayloadVerificationPlugin.INSTANCE);
            PLUGINS_ENABLED = !plugins.isEmpty();
            PacketInfo.enablePayloadVerification = false;
        }
    }

    public static boolean isPayloadVerificationEnabled()
    {
        return PacketInfo.enablePayloadVerification;
    }

    public static void enableNodeTracing(boolean enable)
    {
        TRACE_ENABLED = enable;
    }

    public static boolean isNodeTracingEnabled()
    {
        return TRACE_ENABLED;
    }

    protected String name;

    private Node nextNode;
    private List<Node> inputNodes;

    // Create these once here so we don't allocate a new string every time
    protected final String nodeEntryString;
    protected final String nodeExitString;

    protected Node(String name)
    {
        this.name = name;
        this.nodeEntryString = "Entered node " + name;
        this.nodeExitString = "Exited node " + name;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public ObjectNode statsJson()
    {
        return JsonNodeFactory.instance.objectNode();
    }

    public void visit(NodeVisitor visitor)
    {
        visitor.visit(this);
    }

    /**
     * Marking this as overridable since {@link DemuxerNode} wants to throw an exception
     * if attach is called.
     */
    public Node attach(Node node)
    {
        // Remove ourselves as an input from the node we're currently connected to
        if (nextNode != null)
        {
            throw new RuntimeException("Attempt to replace a Node's child. If this is intentional, use detachNext first.");
        }
        nextNode = node;
        node.addParent(this);

        return node;
    }

    public void detachNext()
    {
        if (nextNode != null)
        {
            nextNode.removeParent(this);
        }
        nextNode = null;
    }

    public void addParent(Node newParent)
    {
        inputNodes().add(newParent);
    }

    public void removeParent(Node parent)
    {
        inputNodes().remove(parent);
    }

    public Collection<Node> getChildren()
    {
        if (nextNode == null)
        {
            return Collections.emptyList();
        }
        return Collections.singletonList(nextNode);
    }

    public Collection<Node> getParents()
    {
        return inputNodes();
    }

    private List<Node> inputNodes()
    {
        if (inputNodes == null)
        {
            inputNodes = new ArrayList<>();
        }
        return inputNodes;
    }

    @Override
    public void handleEvent(Event event)
    {
        // No-op by default
    }

    @Override
    public void stop()
    {
        // No-op by default
    }

    protected void next(PacketInfo packetInfo)
    {
        if (PLUGINS_ENABLED)
        {
            for (NodePlugin plugin : plugins)
            {
                plugin.observe(this, packetInfo);
            }
        }
        if (nextNode != null)
        {
            nextNode.processPacket(packetInfo);
        }
    }

    protected void next(List<PacketInfo> packetInfos)
    {
        for (PacketInfo packetInfo : packetInfos)
        {
            if (PLUGINS_ENABLED)
            {
                for (NodePlugin plugin : plugins)
                {
                    plugin.observe(this, packetInfo);
                }
            }
            if (nextNode != null)
            {
                nextNode.processPacket(packetInfo);
            }
        }
    }

    /**
     * This function must be implemented by leaf nodes, as
     * <pre>
     *     protected void trace(Runnable f) { f.run(); }
     * </pre>
     * When {@link #TRACE_ENABLED} is
     * turned on, this ensures that call stacks always include a method from
     * the derived class, rather than one of the parent classes.  This can greatly
     * aid debugging and profiling.
     */
    protected abstract void trace(Runnable f);
}
