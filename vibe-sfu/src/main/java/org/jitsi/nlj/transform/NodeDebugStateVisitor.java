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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.DebugStateMode;
import org.jitsi.nlj.transform.node.Node;

public class NodeDebugStateVisitor extends NodeVisitor
{
    private final ObjectNode o;
    private final DebugStateMode mode;

    public NodeDebugStateVisitor(ObjectNode o, DebugStateMode mode)
    {
        this.o = o;
        this.mode = mode;
    }

    @Override
    protected void doWork(Node node)
    {
        ObjectNode debugState = mode == DebugStateMode.FULL ? node.getNodeStats().toJson() : node.statsJson();
        if (!debugState.isEmpty())
        {
            o.set(node.getName(), debugState);
        }
    }
}
