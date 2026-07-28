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

import org.jitsi.nlj.transform.node.DemuxerNode;
import org.jitsi.nlj.transform.node.Node;

import java.util.ArrayList;
import java.util.List;

public class NodeTeardownVisitor extends NodeVisitor
{
    @Override
    protected void doWork(Node node)
    {
        node.stop();
        if (node instanceof DemuxerNode)
        {
            ((DemuxerNode) node).removePacketPaths();
        }
        else
        {
            node.detachNext();
        }
    }

    @Override
    public void visit(Node node)
    {
        for (Node child : node.getChildren())
        {
            this.visit(child);
        }
        doWork(node);
    }

    @Override
    public void reverseVisit(Node node)
    {
        // We can't use the default reverseVisit method, because in doWork
        // we modify the parents list, so we'll get a ConcurrentModificationException.
        // So instead we override the method here and make a copy of the parents
        // and use that to iterate over so we can modify the real one
        List<Node> parentsCopy = new ArrayList<>(node.getParents());
        for (Node parent : parentsCopy)
        {
            this.reverseVisit(parent);
        }
        doWork(node);
    }
}
