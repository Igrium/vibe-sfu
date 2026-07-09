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

import org.jitsi.nlj.transform.node.Node;

import java.util.HashSet;
import java.util.Set;

/**
 * Produces a set of all notes in the tree.
 */
public class NodeSetVisitor extends NodeVisitor
{
    private final Set<Node> nodeSet;

    public NodeSetVisitor()
    {
        this(new HashSet<>());
    }

    public NodeSetVisitor(Set<Node> nodeSet)
    {
        this.nodeSet = nodeSet;
    }

    public Set<Node> getNodeSet()
    {
        return nodeSet;
    }

    @Override
    protected void doWork(Node node)
    {
        nodeSet.add(node);
    }
}
