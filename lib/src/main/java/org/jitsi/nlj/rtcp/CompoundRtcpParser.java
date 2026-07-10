/*
 * Copyright @ 2019 - Present, 8x8 Inc
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
package org.jitsi.nlj.rtcp;

import org.jitsi.nlj.transform.node.PacketParser;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtcp.CompoundRtcpPacket;
import org.jitsi.utils.logging2.Logger;

/**
 * (Deviation: upstream declares both {@code CompoundRtcpParser} and {@code SingleRtcpParser} in a single
 * {@code RtcpParsers.kt} file; Java requires one public top-level class per file, so this is split in two.)
 */
public class CompoundRtcpParser extends PacketParser
{
    public CompoundRtcpParser(Logger parentLogger)
    {
        super("Compound RTCP parser", parentLogger, CompoundRtcpParser::parse);
    }

    private static Packet parse(Packet packet) throws Exception
    {
        CompoundRtcpPacket compoundPacket = new CompoundRtcpPacket(packet.buffer, packet.offset, packet.length);
        // Force packets to be evaluated to trigger any parsing errors
        compoundPacket.getPackets();
        return compoundPacket;
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }
}
