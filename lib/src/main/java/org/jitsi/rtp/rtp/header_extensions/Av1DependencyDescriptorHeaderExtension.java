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
package org.jitsi.rtp.rtp.header_extensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.util.BitWriter;

import java.util.List;

/**
 * The AV1 Dependency Descriptor header extension, as defined in https://aomediacodec.github.io/av1-rtp-spec/#appendix
 */
@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
public class Av1DependencyDescriptorHeaderExtension extends Av1DependencyDescriptorStatelessSubset
{
    private Integer activeDecodeTargetsBitmask;

    private final List<DTI> customDtis;
    private final List<Integer> customFdiffs;
    private final List<Integer> customChains;

    private final Av1TemplateDependencyStructure structure;

    private FrameInfo frameInfo;

    public Av1DependencyDescriptorHeaderExtension(
        boolean startOfFrame,
        boolean endOfFrame,
        int frameDependencyTemplateId,
        int frameNumber,

        Av1TemplateDependencyStructure newTemplateDependencyStructure,

        Integer activeDecodeTargetsBitmask,

        List<DTI> customDtis,
        List<Integer> customFdiffs,
        List<Integer> customChains,

        Av1TemplateDependencyStructure structure
    )
    {
        super(startOfFrame, endOfFrame, frameDependencyTemplateId, frameNumber, newTemplateDependencyStructure);
        this.activeDecodeTargetsBitmask = activeDecodeTargetsBitmask;
        this.customDtis = customDtis;
        this.customFdiffs = customFdiffs;
        this.customChains = customChains;
        this.structure = structure;
    }

    public Integer getActiveDecodeTargetsBitmask()
    {
        return activeDecodeTargetsBitmask;
    }

    public void setActiveDecodeTargetsBitmask(Integer activeDecodeTargetsBitmask)
    {
        this.activeDecodeTargetsBitmask = activeDecodeTargetsBitmask;
    }

    public List<DTI> getCustomDtis()
    {
        return customDtis;
    }

    public List<Integer> getCustomFdiffs()
    {
        return customFdiffs;
    }

    public List<Integer> getCustomChains()
    {
        return customChains;
    }

    public Av1TemplateDependencyStructure getStructure()
    {
        return structure;
    }

    public synchronized FrameInfo getFrameInfo()
    {
        if (frameInfo == null)
        {
            int templateIndex = (getFrameDependencyTemplateId() + 64 - structure.getTemplateIdOffset()) % 64;
            if (templateIndex >= structure.getTemplateCount())
            {
                int maxTemplate = (structure.getTemplateIdOffset() + structure.getTemplateCount() - 1) % 64;
                throw new Av1DependencyException(
                    "Invalid template ID " + getFrameDependencyTemplateId() + ". " +
                        "Should be in range " + structure.getTemplateIdOffset() + " .. " + maxTemplate + ". " +
                        "Missed a keyframe?"
                );
            }
            FrameInfo templateVal = structure.getTemplateInfo().get(templateIndex);

            frameInfo = new FrameInfo(
                templateVal.getSpatialId(),
                templateVal.getTemporalId(),
                customDtis != null ? customDtis : templateVal.getDti(),
                customFdiffs != null ? customFdiffs : templateVal.getFdiff(),
                customChains != null ? customChains : templateVal.getChains()
            );
        }
        return frameInfo;
    }

    public int getEncodedLength()
    {
        return (getUnpaddedLengthBits() + 7) / 8;
    }

    private int getUnpaddedLengthBits()
    {
        int length = 24;
        Av1TemplateDependencyStructure newTemplateDependencyStructure = getNewTemplateDependencyStructure();
        if (newTemplateDependencyStructure != null ||
            activeDecodeTargetsBitmask != null ||
            customDtis != null ||
            customFdiffs != null ||
            customChains != null
        )
        {
            length += 5;
        }
        if (newTemplateDependencyStructure != null)
        {
            length += newTemplateDependencyStructure.getUnpaddedLengthBits();
        }
        if (activeDecodeTargetsBitmask != null &&
            (
                newTemplateDependencyStructure == null ||
                    activeDecodeTargetsBitmask != ((1 << newTemplateDependencyStructure.getDecodeTargetCount()) - 1)
            )
        )
        {
            length += structure.getDecodeTargetCount();
        }
        if (customDtis != null)
        {
            length += 2 * structure.getDecodeTargetCount();
        }
        if (customFdiffs != null)
        {
            for (int fdiff : customFdiffs)
            {
                length += 2 + bitsForFdiff(fdiff);
            }
            length += 2;
        }
        if (customChains != null)
        {
            length += 8 * customChains.size();
        }

        return length;
    }

    @Override
    public Av1DependencyDescriptorHeaderExtension clone()
    {
        Av1TemplateDependencyStructure structureCopy = structure.clone();
        Av1TemplateDependencyStructure newStructure = getNewTemplateDependencyStructure() == null ?
            null : structureCopy;
        return new Av1DependencyDescriptorHeaderExtension(
            isStartOfFrame(),
            isEndOfFrame(),
            getFrameDependencyTemplateId(),
            getFrameNumber(),
            newStructure,
            activeDecodeTargetsBitmask,
            // These values are not mutable so it's safe to copy them by reference
            customDtis,
            customFdiffs,
            customChains,
            structureCopy
        );
    }

    public void write(RtpPacket.HeaderExtension ext)
    {
        write(ext.getBuffer(), ext.getDataOffset(), ext.getDataLengthBytes());
    }

    public void write(byte[] buffer, int offset, int length)
    {
        if (!(length <= getEncodedLength()))
        {
            throw new IllegalStateException(
                "Cannot write AV1 DD to buffer: buffer length " + length + " must be at least " + getEncodedLength()
            );
        }
        BitWriter writer = new BitWriter(buffer, offset, length);

        writeMandatoryDescriptorFields(writer);

        Av1TemplateDependencyStructure newTemplateDependencyStructure = getNewTemplateDependencyStructure();
        if (newTemplateDependencyStructure != null ||
            activeDecodeTargetsBitmask != null ||
            customDtis != null ||
            customFdiffs != null ||
            customChains != null
        )
        {
            writeOptionalDescriptorFields(writer);
            writePadding(writer);
        }
        else
        {
            if (!(length == 3))
            {
                throw new IllegalStateException("AV1 DD without optional descriptors must be 3 bytes in length");
            }
        }
    }

    private void writeMandatoryDescriptorFields(BitWriter writer)
    {
        writer.writeBit(isStartOfFrame());
        writer.writeBit(isEndOfFrame());
        writer.writeBits(6, getFrameDependencyTemplateId());
        writer.writeBits(16, getFrameNumber());
    }

    private void writeOptionalDescriptorFields(BitWriter writer)
    {
        Av1TemplateDependencyStructure newTemplateDependencyStructure = getNewTemplateDependencyStructure();
        boolean templateDependencyStructurePresent = newTemplateDependencyStructure != null;
        boolean activeDecodeTargetsPresent = activeDecodeTargetsBitmask != null &&
            (
                newTemplateDependencyStructure == null ||
                    activeDecodeTargetsBitmask != ((1 << newTemplateDependencyStructure.getDecodeTargetCount()) - 1)
            );

        boolean customDtisFlag = customDtis != null;
        boolean customFdiffsFlag = customFdiffs != null;
        boolean customChainsFlag = customChains != null;

        writer.writeBit(templateDependencyStructurePresent);
        writer.writeBit(activeDecodeTargetsPresent);
        writer.writeBit(customDtisFlag);
        writer.writeBit(customFdiffsFlag);
        writer.writeBit(customChainsFlag);

        if (templateDependencyStructurePresent)
        {
            newTemplateDependencyStructure.write(writer);
        }

        if (activeDecodeTargetsPresent)
        {
            writeActiveDecodeTargets(writer);
        }

        if (customDtisFlag)
        {
            writeFrameDtis(writer);
        }

        if (customFdiffsFlag)
        {
            writeFrameFdiffs(writer);
        }

        if (customChainsFlag)
        {
            writeFrameChains(writer);
        }
    }

    private void writeActiveDecodeTargets(BitWriter writer)
    {
        writer.writeBits(structure.getDecodeTargetCount(), activeDecodeTargetsBitmask);
    }

    private void writeFrameDtis(BitWriter writer)
    {
        for (DTI dti : customDtis)
        {
            writer.writeBits(2, dti.getDti());
        }
    }

    private void writeFrameFdiffs(BitWriter writer)
    {
        for (int fdiff : customFdiffs)
        {
            int bits = bitsForFdiff(fdiff);
            writer.writeBits(2, bits / 4);
            writer.writeBits(bits, fdiff - 1);
        }
        writer.writeBits(2, 0);
    }

    private void writeFrameChains(BitWriter writer)
    {
        for (int chain : customChains)
        {
            writer.writeBits(8, chain);
        }
    }

    private void writePadding(BitWriter writer)
    {
        writer.writeBits(writer.getRemainingBits(), 0);
    }

    @Override
    public String toString()
    {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("startOfFrame", isStartOfFrame());
        node.put("endOfFrame", isEndOfFrame());
        node.put("frameDependencyTemplateId", getFrameDependencyTemplateId());
        node.put("frameNumber", getFrameNumber());
        Av1TemplateDependencyStructure newTemplateDependencyStructure = getNewTemplateDependencyStructure();
        if (newTemplateDependencyStructure != null)
        {
            node.put("templateStructure", newTemplateDependencyStructure.toString());
        }
        if (customDtis != null)
        {
            node.set("customDTIs", (ArrayNode) mapper.valueToTree(customDtis));
        }
        if (customFdiffs != null)
        {
            node.set("customFdiffs", (ArrayNode) mapper.valueToTree(customFdiffs));
        }
        if (customChains != null)
        {
            node.set("customChains", (ArrayNode) mapper.valueToTree(customChains));
        }
        if (activeDecodeTargetsBitmask != null)
        {
            if (newTemplateDependencyStructure == null ||
                activeDecodeTargetsBitmask != ((1 << newTemplateDependencyStructure.getDecodeTargetCount()) - 1))
            {
                node.put("activeDecodeTargets", Integer.toBinaryString(activeDecodeTargetsBitmask));
            }
        }
        return node.toString();
    }

    private static int bitsForFdiff(int value)
    {
        if (value <= 0x10)
        {
            return 4;
        }
        else if (value <= 0x100)
        {
            return 8;
        }
        else if (value <= 0x1000)
        {
            return 12;
        }
        else
        {
            throw new IllegalArgumentException("Invalid FDiff value " + value);
        }
    }
}
