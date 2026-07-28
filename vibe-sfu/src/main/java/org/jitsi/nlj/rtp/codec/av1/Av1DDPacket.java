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

import org.jitsi.nlj.RtpEncodingDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.rtp.ParsedVideoPacket;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorHeaderExtension;
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorReader;
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorStatelessSubset;
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyException;
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure;
import org.jitsi.rtp.rtp.header_extensions.FrameInfo;
import org.jitsi.utils.logging2.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** A video packet carrying an AV1 Dependency Descriptor.  Note that this may or may not be an actual AV1 packet;
 * other video codecs can also carry the AV1 DD.
 */
public class Av1DDPacket extends ParsedVideoPacket
{
    private Av1DependencyDescriptorHeaderExtension descriptor;
    private final Av1DependencyDescriptorStatelessSubset statelessDescriptor;
    private final FrameInfo frameInfo;
    private final int av1DDHeaderExtensionId;

    private Av1DDPacket(
        byte[] buffer,
        int offset,
        int length,
        int av1DDHeaderExtensionId,
        int encodingId,
        Av1DependencyDescriptorHeaderExtension descriptor,
        Av1DependencyDescriptorStatelessSubset statelessDescriptor,
        FrameInfo frameInfo
    )
    {
        super(buffer, offset, length, encodingId);
        this.descriptor = descriptor;
        this.statelessDescriptor = statelessDescriptor;
        this.frameInfo = frameInfo;
        this.av1DDHeaderExtensionId = av1DDHeaderExtensionId;
    }

    public Av1DDPacket(
        RtpPacket packet,
        int av1DDHeaderExtensionId,
        Av1TemplateDependencyStructure templateDependencyStructure,
        Logger logger
    )
    {
        super(packet.getBuffer(), packet.getOffset(), packet.getLength(), RtpLayerDesc.SUSPENDED_ENCODING_ID);
        this.av1DDHeaderExtensionId = av1DDHeaderExtensionId;
        RtpPacket.HeaderExtension ddExt = packet.getHeaderExtension(av1DDHeaderExtensionId);
        if (ddExt == null)
        {
            throw new IllegalArgumentException("Packet did not have Dependency Descriptor");
        }
        Av1DependencyDescriptorReader parser = new Av1DependencyDescriptorReader(ddExt);
        Av1DependencyDescriptorHeaderExtension parsedDescriptor;
        try
        {
            parsedDescriptor = parser.parse(templateDependencyStructure);
        }
        catch (Av1DependencyException e)
        {
            logger.warn(
                "Could not parse AV1 Dependency Descriptor for ssrc " + packet.getSsrc() + " seq " +
                    packet.getSequenceNumber() + ": " + e.getMessage()
            );
            parsedDescriptor = null;
        }
        this.descriptor = parsedDescriptor;
        this.statelessDescriptor = descriptor != null ? descriptor : parser.parseStateless();
        FrameInfo parsedFrameInfo;
        try
        {
            parsedFrameInfo = descriptor != null ? descriptor.getFrameInfo() : null;
        }
        catch (Av1DependencyException e)
        {
            logger.warn(
                "Could not extract frame info from AV1 Dependency Descriptor for " +
                    "ssrc " + packet.getSsrc() + " seq " + packet.getSequenceNumber() + ": " + e.getMessage()
            );
            parsedFrameInfo = null;
        }
        this.frameInfo = parsedFrameInfo;
    }

    public Av1DependencyDescriptorHeaderExtension getDescriptor()
    {
        return descriptor;
    }

    public void setDescriptor(Av1DependencyDescriptorHeaderExtension descriptor)
    {
        this.descriptor = descriptor;
    }

    public Av1DependencyDescriptorStatelessSubset getStatelessDescriptor()
    {
        return statelessDescriptor;
    }

    public FrameInfo getFrameInfo()
    {
        return frameInfo;
    }

    public int getAv1DDHeaderExtensionId()
    {
        return av1DDHeaderExtensionId;
    }

    /* "template_dependency_structure_present_flag MUST be set to 1 for the first packet of a coded video sequence,
     * and MUST be set to 0 otherwise"
     */
    @Override
    public boolean isKeyframe()
    {
        return statelessDescriptor.getNewTemplateDependencyStructure() != null;
    }

    @Override
    public boolean isStartOfFrame()
    {
        return statelessDescriptor.isStartOfFrame();
    }

    @Override
    public boolean isEndOfFrame()
    {
        return statelessDescriptor.isEndOfFrame();
    }

    @Override
    public boolean meetsRoutingNeeds()
    {
        return true; // If it didn't parse as AV1 we would have failed in the constructor
    }

    @Override
    public Collection<Integer> getLayerIds()
    {
        return frameInfo != null ? frameInfo.getDtisPresent() : super.getLayerIds();
    }

    public int getFrameNumber()
    {
        return statelessDescriptor.getFrameNumber();
    }

    public Integer getActiveDecodeTargets()
    {
        return descriptor != null ? descriptor.getActiveDecodeTargetsBitmask() : null;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(super.toString());
        sb.append(", DTIs=").append(frameInfo != null ? frameInfo.getDtisPresent() : null);
        Integer activeDecodeTargets = getActiveDecodeTargets();
        if (activeDecodeTargets != null)
        {
            sb.append(", ActiveTargets=").append(activeDecodeTargets);
        }
        return sb.toString();
    }

    @Override
    public Av1DDPacket clone()
    {
        Av1DependencyDescriptorHeaderExtension descriptorClone = descriptor != null ? descriptor.clone() : null;
        Av1DependencyDescriptorStatelessSubset statelessDescriptorClone =
            descriptorClone != null ? descriptorClone : statelessDescriptor.clone();
        Av1DDPacket clone = new Av1DDPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            av1DDHeaderExtensionId,
            getEncodingId(),
            descriptorClone,
            statelessDescriptorClone,
            frameInfo
        );
        postClone(clone);
        return clone;
    }

    public RtpEncodingDesc getScalabilityStructure()
    {
        return getScalabilityStructure(0, 30.0);
    }

    public RtpEncodingDesc getScalabilityStructure(int eid)
    {
        return getScalabilityStructure(eid, 30.0);
    }

    public RtpEncodingDesc getScalabilityStructure(int eid, double baseFrameRate)
    {
        if (descriptor == null)
        {
            throw new IllegalStateException("Can't get scalability structure from packet without a descriptor");
        }
        return getScalabilityStructure(descriptor, getSsrc(), eid, baseFrameRate);
    }

    /** Re-encode the current descriptor to the header extension.  For use after modifying it. */
    public void reencodeDdExt()
    {
        if (descriptor == null)
        {
            throw new IllegalStateException("Can't re-encode extension from a packet without a descriptor");
        }

        RtpPacket.HeaderExtension ext = getHeaderExtension(av1DDHeaderExtensionId);
        if (ext == null || ext.getDataLengthBytes() != descriptor.getEncodedLength())
        {
            removeHeaderExtension(av1DDHeaderExtensionId);
            ext = addHeaderExtension(av1DDHeaderExtensionId, descriptor.getEncodedLength());
        }
        descriptor.write(ext);
    }

    /** Get the scalability structure described by an {@link Av1DependencyDescriptorHeaderExtension}. */
    public static RtpEncodingDesc getScalabilityStructure(
        Av1DependencyDescriptorHeaderExtension descriptor,
        long ssrc,
        int eid,
        double baseFrameRate
    )
    {
        Integer activeDecodeTargetsBitmask = descriptor.getActiveDecodeTargetsBitmask();
        if (activeDecodeTargetsBitmask == null)
        {
            // Can't get scalability structure from dependency descriptor that doesn't specify decode targets
            return null;
        }

        Av1TemplateDependencyStructure structure = descriptor.getStructure();

        int[][] layerCounts = new int[structure.getMaxSpatialId() + 1][structure.getMaxTemporalId() + 1];

        // Figure out the frame rates per spatial/temporal layer.
        for (FrameInfo t : structure.getTemplateInfo())
        {
            if (!t.hasInterPictureDependency())
            {
                // This is a template that doesn't reference any previous frames, so is probably a key frame or
                // part of the same temporal picture with one, i.e. not part of the regular structure.
                continue;
            }
            layerCounts[t.getSpatialId()][t.getTemporalId()]++;
        }

        // Sum up counts per spatial layer
        for (int[] a : layerCounts)
        {
            int total = 0;
            for (int i = 0; i < a.length; i++)
            {
                int entry = a[i];
                a[i] += total;
                total += entry;
            }
        }

        int maxFrameGroup = 0;
        for (int[] a : layerCounts)
        {
            for (int v : a)
            {
                if (v > maxFrameGroup)
                {
                    maxFrameGroup = v;
                }
            }
        }

        List<Av1DDRtpLayerDesc> layers = new ArrayList<>();

        List<Av1TemplateDependencyStructure.DecodeTargetLayer> decodeTargetLayers = structure.getDecodeTargetLayers();
        for (int i = 0; i < decodeTargetLayers.size(); i++)
        {
            Av1TemplateDependencyStructure.DecodeTargetLayer dt = decodeTargetLayers.get(i);
            if (!containsDecodeTarget(activeDecodeTargetsBitmask, i))
            {
                continue;
            }
            // Treat the lesser of width and height as the height in order to handle portrait-mode video correctly
            int height = -1;
            List<Av1TemplateDependencyStructure.Resolution> maxRenderResolutions = structure.getMaxRenderResolutions();
            if (dt.getSpatialId() < maxRenderResolutions.size())
            {
                Av1TemplateDependencyStructure.Resolution r = maxRenderResolutions.get(dt.getSpatialId());
                height = Math.min(r.getWidth(), r.getHeight());
            }

            // Calculate the fraction of this spatial layer's framerate this DT comprises.
            double frameRate = baseFrameRate * layerCounts[dt.getSpatialId()][dt.getTemporalId()] / maxFrameGroup;

            layers.add(new Av1DDRtpLayerDesc(eid, i, dt.getTemporalId(), dt.getSpatialId(), height, frameRate));
        }
        return new RtpEncodingDesc(ssrc, layers.toArray(new Av1DDRtpLayerDesc[0]), eid);
    }

    /** Check whether an activeDecodeTargetsBitmask contains a specific decode target. */
    public static boolean containsDecodeTarget(int activeDecodeTargetsBitmask, int dt)
    {
        return ((1 << dt) & activeDecodeTargetsBitmask) != 0;
    }

    /**
     * Returns the delta between two AV1 templateID values, taking into account
     * rollover.  This will return the 'positive' delta between the two
     * picture IDs in the form of the number you'd add to b to get a. e.g.:
     * getTl0PicIdxDelta(1, 10) -&gt; 55 (10 + 55 = 1)
     * getTl0PicIdxDelta(1, 58) -&gt; 7 (58 + 7 = 1)
     */
    public static int getTemplateIdDelta(int a, int b)
    {
        return (a - b + 64) % 64;
    }

    /**
     * Apply a delta to a given templateID and return the result (taking
     * rollover into account)
     * @param start the starting templateID
     * @param delta the delta to be applied
     * @return the templateID resulting from doing "start + delta"
     */
    public static int applyTemplateIdDelta(int start, int delta)
    {
        return (start + delta) % 64;
    }
}
