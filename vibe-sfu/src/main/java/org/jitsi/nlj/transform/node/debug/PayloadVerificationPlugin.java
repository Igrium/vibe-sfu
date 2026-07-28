/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.transform.node.debug;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.transform.node.Node;
import org.jitsi.nlj.transform.node.NodePlugin;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that the payload verification string of the packet hasn't changed.
 *
 * @author Boris Grozev
 */
public class PayloadVerificationPlugin implements NodePlugin
{
    public static final PayloadVerificationPlugin INSTANCE = new PayloadVerificationPlugin();

    private static final Logger logger = new LoggerImpl(PayloadVerificationPlugin.class.getName());

    public static final AtomicInteger numFailures = new AtomicInteger();

    private PayloadVerificationPlugin()
    {
    }

    public static ObjectNode getStatsJson()
    {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("num_payload_verification_failures", numFailures.get());
        return o;
    }

    @Override
    public void observe(Node after, PacketInfo packetInfo)
    {
        if (PacketInfo.enablePayloadVerification && packetInfo.getPayloadVerification() != null)
        {
            String expected = packetInfo.getPayloadVerification();
            String actual = packetInfo.getPacket().getPayloadVerification();
            if (!expected.equals(actual))
            {
                logger.warn(() -> "Payload unexpectedly modified by " + after.getName() + "! Expected: " + expected + ", actual: " + actual);
                numFailures.incrementAndGet();
                packetInfo.resetPayloadVerification();
            }
        }
    }
}
