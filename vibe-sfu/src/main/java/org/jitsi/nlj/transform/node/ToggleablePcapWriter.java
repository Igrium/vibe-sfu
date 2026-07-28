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
import org.jitsi.utils.logging2.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

public class ToggleablePcapWriter
{
    /**
     * Replaces the upstream {@code jitsi-metaconfig}-based config. Since this port does not use
     * {@code jitsi-metaconfig}/HOCON, this hardcodes the upstream defaults (see {@code reference.conf}'s
     * {@code jmt.debug.pcap} section).
     */
    private static final boolean allowed = false;
    private static final boolean startEnabled = false;

    private final Logger parentLogger;
    private final String prefix;
    private PcapWriter pcapWriter;
    private final Object pcapLock = new Object();

    public ToggleablePcapWriter(Logger parentLogger, String prefix)
    {
        this.parentLogger = parentLogger;
        this.prefix = prefix;

        if (startEnabled)
        {
            enable();
        }
    }

    public void enable()
    {
        if (!allowed)
        {
            throw new IllegalStateException("PCAP capture is disabled in configuration");
        }

        synchronized (pcapLock)
        {
            if (pcapWriter == null)
            {
                Path path = Paths.get(PcapWriter.directory, prefix + "-" + new Date().toInstant() + ".pcap");
                pcapWriter = new PcapWriter(parentLogger, path);
            }
        }
    }

    public void disable()
    {
        synchronized (pcapLock)
        {
            if (pcapWriter != null)
            {
                pcapWriter.close();
            }
            pcapWriter = null;
        }
    }

    public boolean isEnabled()
    {
        return pcapWriter != null;
    }

    public PcapWriterNode newObserverNode(boolean outbound, String suffix)
    {
        return new PcapWriterNode("ToggleablePcapWriter_" + suffix, outbound);
    }

    public class PcapWriterNode extends ObserverNode
    {
        private final boolean outbound;

        public PcapWriterNode(String name, boolean outbound)
        {
            super(name);
            this.outbound = outbound;
        }

        @Override
        protected void observe(PacketInfo packetInfo)
        {
            if (pcapWriter != null)
            {
                pcapWriter.observe(packetInfo, outbound);
            }
        }

        public void observe(byte[] buffer, int offset, int length)
        {
            if (pcapWriter != null)
            {
                pcapWriter.observe(buffer, offset, length, outbound);
            }
        }

        @Override
        public void trace(Runnable f)
        {
            f.run();
        }
    }
}
