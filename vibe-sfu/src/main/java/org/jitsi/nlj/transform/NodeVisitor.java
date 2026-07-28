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

public abstract class NodeVisitor
{
    public void visit(Node node)
    {
        doWork(node);
        for (Node child : node.getChildren())
        {
            this.visit(child);
        }
    }

    /**
     * {@link #reverseVisit} is used for traversing an 'outgoing'-style tree which
     * has many input paths but terminates at a single point (as opposed to an
     * 'incoming'-style tree which starts at a single point and then branches
     * into multiple paths.  With reverseVisit, we start at the single terminating
     * point and traverse backwards through the tree.  It should be noted, however,
     * that reverseVisit is done in a 'postorder' traversal style (meaning that a {@link Node}'s
     * 'inputNodes' are visited before that Node itself.
     * TODO: protect against visiting the same node twice in the event of a cycle (which
     * we should do for 'visit' as well)
     */
    public void reverseVisit(Node node)
    {
        // NOTE(brian): although we're doing a reverse visit here, we still visit the nodes
        // in 'forward' order by going up through all the parents first and then calling
        // doWork on our way back 'down', as it's usually more useful to do things like
        // view the stats in the 'normal' order and just use the 'reverse' visit as a
        // way to handle the 'outgoing' tree style (as mentioned in the comment above)
        for (Node parent : node.getParents())
        {
            this.reverseVisit(parent);
        }
        doWork(node);
    }

    protected abstract void doWork(Node node);
}
