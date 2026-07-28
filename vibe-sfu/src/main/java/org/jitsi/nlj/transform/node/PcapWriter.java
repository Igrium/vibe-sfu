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
import org.pcap4j.core.PcapDumper;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.EthernetPacket;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV4Rfc1349Tos;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.DataLinkType;
import org.pcap4j.packet.namednumber.EtherType;
import org.pcap4j.packet.namednumber.IpNumber;
import org.pcap4j.packet.namednumber.IpVersion;
import org.pcap4j.packet.namednumber.UdpPort;
import org.pcap4j.util.MacAddress;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

public class PcapWriter
{
    /**
     * Replaces the upstream {@code jitsi-metaconfig}-based config. Since this port does not use
     * {@code jitsi-metaconfig}/HOCON, this hardcodes the upstream default (see {@code reference.conf}'s
     * {@code jmt.debug.pcap.directory}).
     */
    public static final String directory = "/tmp";

    private static final Inet4Address localhost;
    private static final Inet4Address remotehost;

    static
    {
        try
        {
            localhost = (Inet4Address) Inet4Address.getByName("127.0.0.1");
            remotehost = (Inet4Address) Inet4Address.getByName("192.0.2.0");
        }
        catch (UnknownHostException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static final UdpPort localport = new UdpPort((short) 123, "blah");
    private static final UdpPort remoteport = new UdpPort((short) 456, "blah");

    private final Logger logger;
    private final Path filePath;

    private PcapHandle handle;
    private PcapDumper writer;
    private final Object lock = new Object();

    public PcapWriter(Logger parentLogger)
    {
        this(parentLogger, Paths.get(directory, new Random().nextLong() + ".pcap"));
    }

    public PcapWriter(Logger parentLogger, String filePath)
    {
        this(parentLogger, Paths.get(filePath));
    }

    public PcapWriter(Logger parentLogger, Path filePath)
    {
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.filePath = filePath;
    }

    private PcapHandle getHandle()
    {
        synchronized (lock)
        {
            if (handle == null)
            {
                try
                {
                    handle = Pcaps.openDead(DataLinkType.EN10MB, 65536);
                }
                catch (org.pcap4j.core.PcapNativeException e)
                {
                    throw new RuntimeException(e);
                }
            }
            return handle;
        }
    }

    private PcapDumper getWriter()
    {
        synchronized (lock)
        {
            if (writer == null)
            {
                logger.info(() -> "Pcap writer writing to file " + filePath);
                try
                {
                    writer = getHandle().dumpOpen(filePath.toString());
                }
                catch (org.pcap4j.core.NotOpenException | org.pcap4j.core.PcapNativeException e)
                {
                    throw new RuntimeException(e);
                }
            }
            return writer;
        }
    }

    public void observe(PacketInfo packetInfo, boolean outbound)
    {
        observe(packetInfo.getPacket().buffer, packetInfo.getPacket().offset, packetInfo.getPacket().length, outbound);
    }

    public void observe(byte[] buffer, int offset, int length, boolean outbound)
    {
        UnknownPacket.Builder udpPayload = new UnknownPacket.Builder();
        // We can't pass offset/limit values to udpPayload.rawData, so we need to create an array that contains
        // only exactly what we want to write
        byte[] subBuf = new byte[length];
        System.arraycopy(buffer, offset, subBuf, 0, length);
        udpPayload.rawData(subBuf);
        Inet4Address srchost;
        Inet4Address dsthost;
        UdpPort srcport;
        UdpPort dstport;
        if (outbound)
        {
            srchost = localhost;
            srcport = localport;
            dsthost = remotehost;
            dstport = remoteport;
        }
        else
        {
            srchost = remotehost;
            srcport = remoteport;
            dsthost = localhost;
            dstport = localport;
        }

        UdpPacket.Builder udp = new UdpPacket.Builder()
            .srcPort(srcport)
            .dstPort(dstport)
            .srcAddr(srchost)
            .dstAddr(dsthost)
            .correctChecksumAtBuild(true)
            .correctLengthAtBuild(true)
            .payloadBuilder(udpPayload);

        IpV4Packet.Builder ipPacket = new IpV4Packet.Builder()
            .srcAddr(srchost)
            .dstAddr(dsthost)
            .protocol(IpNumber.UDP)
            .version(IpVersion.IPV4)
            .tos(IpV4Rfc1349Tos.newInstance((byte) 0))
            .correctLengthAtBuild(true)
            .payloadBuilder(udp);

        EthernetPacket eth = new EthernetPacket.Builder()
            .srcAddr(MacAddress.ETHER_BROADCAST_ADDRESS)
            .dstAddr(MacAddress.ETHER_BROADCAST_ADDRESS)
            .type(EtherType.IPV4)
            .paddingAtBuild(true)
            .payloadBuilder(ipPacket)
            .build();

        try
        {
            getWriter().dump(eth);
        }
        catch (org.pcap4j.core.NotOpenException e)
        {
            throw new RuntimeException(e);
        }
    }

    public void close()
    {
        synchronized (lock)
        {
            if (writer != null && writer.isOpen())
            {
                writer.close();
            }

            if (handle != null && handle.isOpen())
            {
                handle.close();
            }
        }
    }
}
