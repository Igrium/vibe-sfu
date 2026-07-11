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
package org.jitsi.nlj.transform.node.incoming;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.rtp.LossListener;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.stats.NodeStatsBlock;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.nlj.util.BitrateTracker;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.nlj.util.RtpSequenceIndexTracker;
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Extract the TCC sequence numbers from each passing packet and generate
 * a TCC packet to send transmit to the sender.
 */
public class TccGeneratorNode extends ObserverNode
{
    private static final Duration TCC_INTERVAL = Duration.ofMillis(100);
    private static final Duration TCC_INTERVAL_MARKED = Duration.ofMillis(20);

    private final Consumer<RtcpPacket> onTccPacketReady;
    private final ReadOnlyStreamInformationStore streamInformation;
    private final Clock clock;
    private final Logger logger;
    private Integer tccExtensionId;
    private int currTccSeqNum = 0;
    private Instant lastTccSentTime = InstantKt.NEVER;
    private final Object lock = new Object();

    // Tcc seq num -> arrival time in ms
    private final TreeMap<Long, Instant> packetArrivalTimes = new TreeMap<>();

    // The first sequence number of the current tcc feedback packet
    private long windowStartSeq = -1;
    private final BitrateTracker tccFeedbackBitrate = new BitrateTracker(Duration.ofSeconds(1), Duration.ofMillis(10));
    private int numTccSent = 0;
    private int numMultipleTccPackets = 0;
    private boolean enabled = false;
    private final RtpSequenceIndexTracker rtpSequenceIndexTracker = new RtpSequenceIndexTracker();

    private final List<LossListener> lossListeners = new ArrayList<>();

    public TccGeneratorNode(ReadOnlyStreamInformationStore streamInformation, Logger parentLogger)
    {
        this(rtcpPacket -> { }, streamInformation, parentLogger, Clock.systemDefaultZone());
    }

    public TccGeneratorNode(
        Consumer<RtcpPacket> onTccPacketReady,
        ReadOnlyStreamInformationStore streamInformation,
        Logger parentLogger)
    {
        this(onTccPacketReady, streamInformation, parentLogger, Clock.systemDefaultZone());
    }

    public TccGeneratorNode(
        Consumer<RtcpPacket> onTccPacketReady,
        ReadOnlyStreamInformationStore streamInformation,
        Logger parentLogger,
        Clock clock)
    {
        super("TCC generator");
        this.onTccPacketReady = onTccPacketReady;
        this.streamInformation = streamInformation;
        this.clock = clock;
        this.logger = parentLogger.createChildLogger(getClass().getName());

        streamInformation.onRtpExtensionMapping(RtpExtensionType.TRANSPORT_CC, id -> tccExtensionId = id);
        streamInformation.onRtpPayloadTypesChanged(payloadTypes -> setEnabled(streamInformation.getSupportsTcc()));
    }

    private void setEnabled(boolean newValue)
    {
        if (enabled != newValue)
        {
            logger.debug("Setting enabled=" + newValue);
        }
        enabled = newValue;
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        if (!enabled)
        {
            return;
        }

        Integer tccExtId = tccExtensionId;
        if (tccExtId != null)
        {
            RtpPacket rtpPacket = packetInfo.packetAs();
            RtpPacket.HeaderExtension ext = rtpPacket.getHeaderExtension(tccExtId);
            if (ext != null)
            {
                long tccSeqNum = rtpSequenceIndexTracker.update(TccHeaderExtension.getSequenceNumber(ext));
                addPacket(tccSeqNum, packetInfo.getReceivedTime(), rtpPacket.isMarked(), rtpPacket.getSsrc());
            }
        }
    }

    /**
     * Adds a loss listener to be notified about packet arrival and loss reports.
     * @param listener
     */
    public void addLossListener(LossListener listener)
    {
        synchronized (lock)
        {
            lossListeners.add(listener);
        }
    }

    /**
     * Removes a loss listener.
     * @param listener
     */
    public synchronized void removeLossListener(LossListener listener)
    {
        synchronized (lock)
        {
            lossListeners.remove(listener);
        }
    }

    /**
     * @param tccSeqNum the extended sequence number.
     */
    private void addPacket(long tccSeqNum, Instant timestamp, boolean isMarked, long ssrc)
    {
        synchronized (lock)
        {
            if (packetArrivalTimes.ceilingKey(windowStartSeq) == null)
            {
                // Packets in map are all older than the start of the next tcc feedback packet,
                // remove them
                // TODO: Chrome does something more advanced, keeping older sequences to replay on packet reordering.
                packetArrivalTimes.clear();
            }

            if (timestamp != null)
            {
                if (packetArrivalTimes.isEmpty() && windowStartSeq == -1L)
                {
                    for (LossListener listener : lossListeners)
                    {
                        listener.packetReceived(false);
                    }
                }
                else
                {
                    long oldMax = !packetArrivalTimes.isEmpty() ? packetArrivalTimes.lastKey() : windowStartSeq - 1;
                    if (tccSeqNum > oldMax)
                    {
                        long numLost = tccSeqNum - oldMax - 1;
                        /* TODO: should we squelch for large tcc jumps? */
                        for (LossListener listener : lossListeners)
                        {
                            if (numLost > 0)
                            {
                                listener.packetLost((int) numLost);
                            }
                            listener.packetReceived(false);
                        }
                    }
                    else if (tccSeqNum < windowStartSeq || !packetArrivalTimes.containsKey(tccSeqNum))
                    {
                        /* If we've already cleared the arrival info about this packet, assume it was previously
                         * reported as lost - there are some corner cases where this isn't true, but they should be
                         * rare.
                         */
                        for (LossListener listener : lossListeners)
                        {
                            listener.packetReceived(true);
                        }
                    }
                }

                if (windowStartSeq == -1L || tccSeqNum < windowStartSeq)
                {
                    windowStartSeq = tccSeqNum;
                }

                packetArrivalTimes.putIfAbsent(tccSeqNum, timestamp);
            }
            if (isTccReadyToSend(isMarked))
            {
                for (RtcpFbTccPacket tccPacket : buildFeedback(ssrc))
                {
                    sendTcc(tccPacket);
                }
            }
        }
    }

    private List<RtcpFbTccPacket> buildFeedback(long mediaSsrc)
    {
        synchronized (lock)
        {
            // windowStartSeq is the first sequence number to include in the current feedback, but we may not have
            // received it so the base time shall be the time of the first received packet which will be included
            // in this feedback
            Map.Entry<Long, Instant> firstEntry = packetArrivalTimes.ceilingEntry(windowStartSeq);
            if (firstEntry == null)
            {
                return new ArrayList<>();
            }

            List<RtcpFbTccPacket> tccPackets = new ArrayList<>();
            RtcpFbTccPacketBuilder currentTccPacket =
                new RtcpFbTccPacketBuilder(new RtcpHeaderBuilder(), mediaSsrc, currTccSeqNum++);
            currentTccPacket.SetBase((int) windowStartSeq, firstEntry.getValue());

            long nextSequenceNumber = windowStartSeq;
            java.util.SortedMap<Long, Instant> feedbackBlockPackets = packetArrivalTimes.tailMap(windowStartSeq);
            for (Map.Entry<Long, Instant> entry : feedbackBlockPackets.entrySet())
            {
                long seq = entry.getKey();
                Instant ts = entry.getValue();
                if (!currentTccPacket.AddReceivedPacket((int) seq, ts))
                {
                    tccPackets.add(currentTccPacket.build());
                    currentTccPacket = new RtcpFbTccPacketBuilder(new RtcpHeaderBuilder(), mediaSsrc, currTccSeqNum++);
                    currentTccPacket.SetBase((int) seq, ts);
                    currentTccPacket.AddReceivedPacket((int) seq, ts);
                }
                nextSequenceNumber = seq + 1;
            }

            tccPackets.add(currentTccPacket.build());
            if (tccPackets.size() > 1)
            {
                numMultipleTccPackets++;
                logger.info(
                    "Sending TCC feedback in " + tccPackets.size() + " packets (" +
                        feedbackBlockPackets.size() + " media packets)"
                );
            }
            // The next window will start with the sequence number after the last one we included in the previous
            // feedback
            windowStartSeq = nextSequenceNumber;

            return tccPackets;
        }
    }

    private void sendTcc(RtcpFbTccPacket tccPacket)
    {
        onTccPacketReady.accept(tccPacket);
        logger.debug(() -> "sent TCC packet with seq num " + tccPacket.getFeedbackSeqNum());
        numTccSent++;
        lastTccSentTime = clock.instant();
        tccFeedbackBitrate.update(DataSize.ofBytes(tccPacket.getLength()), clock.millis());
    }

    private boolean isTccReadyToSend(boolean currentPacketMarked)
    {
        Instant now = clock.instant();
        // We don't want to send TCC the very first time we check (which would
        // be after the first packet was added).  So the first time we check,
        // set the last sent time to now to delay sending TCC by at least one 'interval'
        if (lastTccSentTime.equals(InstantKt.NEVER))
        {
            lastTccSentTime = now;
            return false;
        }

        Duration timeSinceLastTcc = Duration.between(lastTccSentTime, now);
        return timeSinceLastTcc.compareTo(TCC_INTERVAL) >= 0 ||
            (timeSinceLastTcc.compareTo(TCC_INTERVAL_MARKED) >= 0 && currentPacketMarked);
    }

    @Override
    public void trace(Runnable f)
    {
        f.run();
    }

    @Override
    public NodeStatsBlock getNodeStats()
    {
        NodeStatsBlock block = super.getNodeStats();
        block.addNumber("num_tcc_packets_sent", numTccSent);
        block.addNumber("tcc_feedback_bitrate_bps", tccFeedbackBitrate.getRate().getBps());
        block.addString("tcc_extension_id", String.valueOf(tccExtensionId));
        block.addNumber("num_multiple_tcc_packets", numMultipleTccPackets);
        block.addBoolean("enabled", enabled);
        return block;
    }

    @Override
    public ObjectNode statsJson()
    {
        ObjectNode json = super.statsJson();
        json.put("num_tcc_packets_sent", numTccSent);
        json.put("tcc_feedback_bitrate_bps", tccFeedbackBitrate.getRate().getBps());
        json.put("tcc_extension_id", String.valueOf(tccExtensionId));
        json.put("num_multiple_tcc_packets", numMultipleTccPackets);
        json.put("enabled", enabled);
        return json;
    }
}
