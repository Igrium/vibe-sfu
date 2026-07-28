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

package org.jitsi.nlj.rtp.codec.av1;

import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.rtp.codec.VideoCodecParser;
import org.jitsi.nlj.util.RtpSequenceIndexTracker;
import org.jitsi.nlj.util.TreeCache;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure;
import org.jitsi.utils.LRUCache;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.utils.logging2.Logger;

import java.util.Map;

/**
 * Some {@link Av1DDPacket} fields are not able to be determined by looking at a single packet with an AV1 DD
 * (for example the template dependency structure is only carried in keyframes).  This class updates the layer
 * descriptions with information from frames, and also diagnoses packet format variants that the Jitsi videobridge
 * won't be able to route.
 */
public class Av1DDParser extends VideoCodecParser
{
    public static final int STATE_HISTORY_SIZE = 500;
    public static final int TEMPLATE_HISTORY_SIZE = 500;

    private static final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(Av1DDParser.class);

    private final Logger logger;
    private final DiagnosticContext diagnosticContext;

    /** History of AV1 templates. */
    private final LRUCache<Long, TemplateHistory> ddStateHistory = new LRUCache<>(STATE_HISTORY_SIZE, true);

    public Av1DDParser(MediaSourceDesc source, Logger parentLogger, DiagnosticContext diagnosticContext)
    {
        super(source);
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.diagnosticContext = diagnosticContext;
    }

    public Av1DDPacket createFrom(RtpPacket packet, int av1DdExtId)
    {
        TemplateHistory history = ddStateHistory.computeIfAbsent(
            packet.getSsrc(), k -> new TemplateHistory(TEMPLATE_HISTORY_SIZE));

        Map.Entry<Long, Av1DdInfo> priorEntry = history.get(packet.getSequenceNumber());

        Av1TemplateDependencyStructure priorStructure =
            priorEntry != null ? priorEntry.getValue().getStructure().clone() : null;

        Av1DDPacket av1Packet = new Av1DDPacket(packet, av1DdExtId, priorStructure, logger);

        Av1TemplateDependencyStructure newStructure =
            av1Packet.getDescriptor() != null ? av1Packet.getDescriptor().getNewTemplateDependencyStructure() : null;
        if (newStructure != null)
        {
            boolean structureChanged = priorStructure == null ||
                newStructure.getTemplateIdOffset() != priorStructure.getTemplateIdOffset();
            history.insert(packet.getSequenceNumber(), new Av1DdInfo(newStructure.clone(), structureChanged));
            logger.debug(() -> "Inserting new structure with templates " + newStructure.getTemplateIdOffset() +
                " .. " + ((newStructure.getTemplateIdOffset() + newStructure.getTemplateCount() - 1) % 64) +
                " for RTP packet ssrc " + packet.getSsrc() + " seq " + packet.getSequenceNumber() + ".  " +
                "Changed from previous: " + structureChanged + ".");
        }

        if (timeSeriesLogger.isTraceEnabled())
        {
            DiagnosticContext.TimeSeriesPoint point = diagnosticContext
                .makeTimeSeriesPoint("av1_parser")
                .addField("rtp.ssrc", packet.getSsrc())
                .addField("rtp.seq", packet.getSequenceNumber())
                .addField("rtp.timestamp", packet.getTimestamp())
                .addField("av1_parser.key", priorEntry != null ? priorEntry.getKey() : null)
                .addField("av1.startOfFrame", av1Packet.getStatelessDescriptor().isStartOfFrame())
                .addField("av1.endOfFrame", av1Packet.getStatelessDescriptor().isEndOfFrame())
                .addField("av1.templateId", av1Packet.getStatelessDescriptor().getFrameDependencyTemplateId())
                .addField("av1.frameNum", av1Packet.getStatelessDescriptor().getFrameNumber())
                .addField("av1.frameInfo", av1Packet.getFrameInfo() != null ? av1Packet.getFrameInfo().toString() : null)
                .addField("av1.structure", newStructure != null)
                .addField("av1.activeTargets", av1Packet.getActiveDecodeTargets());
            Av1TemplateDependencyStructure packetStructure =
                av1Packet.getDescriptor() != null ? av1Packet.getDescriptor().getStructure() : null;
            if (packetStructure != null)
            {
                point.addField("av1.structureIdOffset", packetStructure.getTemplateIdOffset())
                    .addField("av1.templateCount", packetStructure.getTemplateCount())
                    .addField("av1.structureId", System.identityHashCode(packetStructure));
            }
            if (newStructure != null)
            {
                point.addField("av1.newStructureIdOffset", newStructure.getTemplateIdOffset())
                    .addField("av1.newTemplateCount", newStructure.getTemplateCount())
                    .addField("av1.newStructureId", System.identityHashCode(newStructure));
            }
            timeSeriesLogger.trace(point);
        }

        return av1Packet;
    }

    @Override
    public void parse(PacketInfo packetInfo)
    {
        Av1DDPacket av1Packet = packetInfo.<Av1DDPacket>packetAs();
        TemplateHistory history = ddStateHistory.get(av1Packet.getSsrc());

        if (history == null)
        {
            /* Probably getting spammed with SSRCs? */
            logger.warn("History for " + av1Packet.getSsrc() + " disappeared between createFrom and parse!");
            return;
        }

        Integer activeDecodeTargets = av1Packet.getActiveDecodeTargets();

        if (activeDecodeTargets != null)
        {
            boolean changed = history.updateDecodeTargets(av1Packet.getSequenceNumber(), activeDecodeTargets);

            if (changed)
            {
                packetInfo.setLayeringChanged(true);
                logger.debug(() -> "Decode targets for " + av1Packet.getSsrc() + " changed in seq " +
                    av1Packet.getSequenceNumber() + ": now 0x" + Integer.toHexString(activeDecodeTargets) +
                    ".  Updating layering.");

                RtpEncodingDesc enc = findRtpEncodingDesc(av1Packet);
                if (enc != null)
                {
                    RtpEncodingDesc scalabilityStructure = av1Packet.getScalabilityStructure(enc.getEid());
                    if (scalabilityStructure != null)
                    {
                        source.setEncodingLayers(scalabilityStructure.getLayers(), av1Packet.getSsrc());
                    }
                    for (RtpEncodingDesc otherEnc : source.getRtpEncodings())
                    {
                        if (!ddStateHistory.containsKey(otherEnc.getPrimarySSRC()))
                        {
                            source.setEncodingLayers(new RtpLayerDesc[0], otherEnc.getPrimarySSRC());
                        }
                    }
                }
            }
        }
    }

    static class TemplateHistory
    {
        private final RtpSequenceIndexTracker indexTracker = new RtpSequenceIndexTracker();
        private final TreeCache<Av1DdInfo> history;
        private int latestDecodeTargets = -1;
        private long latestDecodeTargetIndex = -1L;

        TemplateHistory(int minHistory)
        {
            history = new TreeCache<>(minHistory);
        }

        Map.Entry<Long, Av1DdInfo> get(int seqNo)
        {
            long index = indexTracker.update(seqNo);
            return history.getEntryBefore(index);
        }

        void insert(int seqNo, Av1DdInfo value)
        {
            long index = indexTracker.update(seqNo);
            history.insert(index, value);
        }

        /** Update the current decode targets.
         *  Return true if the decode target set or the template structure has changed. */
        boolean updateDecodeTargets(int seqNo, int decodeTargets)
        {
            long index = indexTracker.update(seqNo);
            if (index < latestDecodeTargetIndex)
            {
                return false;
            }
            Av1DdInfo entry = history.get(index);
            boolean changed = decodeTargets != latestDecodeTargets || (entry != null && entry.isChanged());
            latestDecodeTargetIndex = index;
            latestDecodeTargets = decodeTargets;
            return changed;
        }
    }

    static class Av1DdInfo
    {
        private final Av1TemplateDependencyStructure structure;
        private final boolean changed;

        Av1DdInfo(Av1TemplateDependencyStructure structure, boolean changed)
        {
            this.structure = structure;
            this.changed = changed;
        }

        Av1TemplateDependencyStructure getStructure()
        {
            return structure;
        }

        boolean isChanged()
        {
            return changed;
        }
    }
}
