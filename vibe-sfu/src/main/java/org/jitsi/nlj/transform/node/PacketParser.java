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

import org.jitsi.nlj.PacketInfo;
import org.jitsi.rtp.Packet;
import org.jitsi.utils.logging2.Logger;

public class PacketParser extends TransformerNode
{
    /**
     * (Deviation: upstream's {@code action} constructor parameter is a Kotlin {@code (Packet) -> Packet}, which
     * Kotlin allows to call checked-exception-throwing Java code without declaring it. The functional interface
     * below declares {@code throws Exception} so lambdas like {@code RtcpPacket::parse} can be passed directly.)
     */
    public interface Action
    {
        Packet apply(Packet packet) throws Exception;
    }

    private final Logger logger;
    private final Action action;

    public PacketParser(String name, Logger parentLogger, Action action)
    {
        super(name);
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.action = action;
    }

    @Override
    protected PacketInfo transform(PacketInfo packetInfo)
    {
        try
        {
            packetInfo.setPacket(action.apply(packetInfo.getPacket()));
            packetInfo.resetPayloadVerification();
            return packetInfo;
        }
        catch (Exception e)
        {
            logger.warn("Error parsing packet: " + e);
            return null;
        }
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
