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

import org.jitsi.rtp.Packet;

import java.util.function.Predicate;

public class ConditionalPacketPath
{
    private String name;
    private Predicate<Packet> predicate;
    private Node path;
    public int packetsAccepted = 0;

    public ConditionalPacketPath()
    {
    }

    public ConditionalPacketPath(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public Predicate<Packet> getPredicate()
    {
        return predicate;
    }

    public void setPredicate(Predicate<Packet> predicate)
    {
        this.predicate = predicate;
    }

    public Node getPath()
    {
        return path;
    }

    public void setPath(Node path)
    {
        this.path = path;
    }
}
