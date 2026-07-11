/*
 * Copyright @ 2024-Present 8x8, Inc
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

import org.jitsi.nlj.Event;
import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.PacketInfo;
import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.SetMediaSourcesEvent;
import org.jitsi.nlj.rtp.RtpExtensionType;
import org.jitsi.nlj.transform.node.ObserverNode;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.VlaExtension;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.utils.logging2.LoggerImpl;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A node which reads the Video Layers Allocation (VLA) RTP header extension and updates the media sources.
 */
public class VlaReaderNode extends ObserverNode
{
    private final Logger logger;
    private Integer vlaExtId;
    private MediaSourceDesc[] mediaSourceDescs = new MediaSourceDesc[0];

    public VlaReaderNode(ReadOnlyStreamInformationStore streamInformationStore)
    {
        this(streamInformationStore, new LoggerImpl(VlaReaderNode.class.getSimpleName()));
    }

    public VlaReaderNode(ReadOnlyStreamInformationStore streamInformationStore, Logger parentLogger)
    {
        super("Video Layers Allocation reader");
        this.logger = parentLogger.createChildLogger(getClass().getName());

        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.VLA, id -> {
            vlaExtId = id;
            logger.debug("VLA extension ID set to " + id);
        });
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event instanceof SetMediaSourcesEvent)
        {
            mediaSourceDescs = ((SetMediaSourcesEvent) event).getMediaSourceDescs().clone();
            logger.debug(() -> "Media sources changed:\n" +
                Arrays.stream(mediaSourceDescs).map(String::valueOf).collect(Collectors.joining(", ")));
        }
    }

    @Override
    protected void observe(PacketInfo packetInfo)
    {
        RtpPacket rtpPacket = packetInfo.packetAs();
        Integer extId = vlaExtId;
        if (extId == null)
        {
            return;
        }
        RtpPacket.HeaderExtension ext = rtpPacket.getHeaderExtension(extId);
        if (ext == null)
        {
            return;
        }

        List<VlaExtension.Stream> vla;
        try
        {
            vla = VlaExtension.parse(ext);
        }
        catch (Exception e)
        {
            logger.warn("Failed to parse VLA extension", e);
            return;
        }

        MediaSourceDesc sourceDesc = MediaSourceDesc.findRtpSource(mediaSourceDescs, rtpPacket);

        logger.debug("Found VLA=" + vla + " for sourceDesc=" + sourceDesc);

        for (int streamIdx = 0; streamIdx < vla.size(); streamIdx++)
        {
            VlaExtension.Stream stream = vla.get(streamIdx);
            RtpEncodingDesc rtpEncoding = null;
            if (sourceDesc != null)
            {
                RtpEncodingDesc[] rtpEncodings = sourceDesc.getRtpEncodings();
                if (rtpEncodings != null && streamIdx < rtpEncodings.length)
                {
                    rtpEncoding = rtpEncodings[streamIdx];
                }
            }

            for (VlaExtension.SpatialLayer spatialLayer : stream.getSpatialLayers())
            {
                int maxTl = spatialLayer.getTargetBitratesKbps().size() - 1;

                List<Long> targetBitratesKbps = spatialLayer.getTargetBitratesKbps();
                for (int tlIdx = 0; tlIdx < targetBitratesKbps.size(); tlIdx++)
                {
                    long targetBitrateKbps = targetBitratesKbps.get(tlIdx);
                    RtpLayerDesc layer = findLayer(rtpEncoding, spatialLayer.getId(), tlIdx);
                    if (layer == null)
                    {
                        continue;
                    }

                    int finalTlIdx = tlIdx;
                    RtpLayerDesc finalLayer = layer;
                    RtpEncodingDesc finalRtpEncoding = rtpEncoding;
                    logger.debug(() -> "Setting target bitrate for rtpEncoding=" + finalRtpEncoding + " layer=" +
                        finalLayer + " to " + Bandwidth.ofKbps(targetBitrateKbps) + " (res=" + spatialLayer.getRes()
                        + ")");
                    layer.setTargetBitrate(Bandwidth.ofKbps(targetBitrateKbps));
                    VlaExtension.ResolutionAndFrameRate res = spatialLayer.getRes();
                    if (res != null)
                    {
                        // Treat the lesser of width and height as the height
                        // in order to handle portrait-mode video correctly
                        int minDimension = Math.min(res.getHeight(), res.getWidth());
                        if (layer.getHeight() > 0 && layer.getHeight() != minDimension)
                        {
                            int oldHeight = layer.getHeight();
                            logger.info(() -> "Updating layer height for source " + sourceDesc.getSourceName() +
                                " encoding " + finalRtpEncoding.getPrimarySSRC() + " layer " +
                                finalLayer.indexString() + " from " + oldHeight + " to " + minDimension);
                        }
                        layer.setHeight(minDimension);
                        /* Presume 2:1 frame rate ratios for temporal layers */
                        double framerateFraction = 1.0 / (1 << (maxTl - finalTlIdx));
                        layer.setFrameRate((double) res.getMaxFramerate() * framerateFraction);
                    }
                }
            }
        }
    }

    private static RtpLayerDesc findLayer(RtpEncodingDesc rtpEncoding, int spatialLayerId, int tlIdx)
    {
        if (rtpEncoding == null)
        {
            return null;
        }
        for (RtpLayerDesc layer : rtpEncoding.getLayers())
        {
            // With VP8 simulcast all layers have sid -1
            if ((layer.getSid() == spatialLayerId || layer.getSid() == -1) && layer.getTid() == tlIdx)
            {
                return layer;
            }
        }
        return null;
    }

    @Override
    public void trace(Runnable f)
    {
    }
}
