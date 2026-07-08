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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jitsi.rtp.util.BitWriter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The template information about a stream described by AV1 dependency descriptors.  This is carried in the
 * first packet of a codec video sequence (i.e. the first packet of a keyframe), and is necessary to interpret
 * dependency descriptors carried in subsequent packets of the sequence.
 */
@SuppressFBWarnings("CN_IMPLEMENTS_CLONE_BUT_NOT_CLONEABLE")
public class Av1TemplateDependencyStructure
{
    private int templateIdOffset;
    private final List<FrameInfo> templateInfo;
    private final List<Integer> decodeTargetProtectedBy;
    private final List<DecodeTargetLayer> decodeTargetLayers;
    private final List<Resolution> maxRenderResolutions;
    private final int maxSpatialId;
    private final int maxTemporalId;

    private final int chainCount;

    public Av1TemplateDependencyStructure(
        int templateIdOffset,
        List<FrameInfo> templateInfo,
        List<Integer> decodeTargetProtectedBy,
        List<DecodeTargetLayer> decodeTargetLayers,
        List<Resolution> maxRenderResolutions,
        int maxSpatialId,
        int maxTemporalId
    )
    {
        this.templateIdOffset = templateIdOffset;
        this.templateInfo = templateInfo;
        this.decodeTargetProtectedBy = decodeTargetProtectedBy;
        this.decodeTargetLayers = decodeTargetLayers;
        this.maxRenderResolutions = maxRenderResolutions;
        this.maxSpatialId = maxSpatialId;
        this.maxTemporalId = maxTemporalId;

        this.chainCount = templateInfo.get(0).getChains().size();

        for (FrameInfo t : templateInfo)
        {
            if (t.getChains().size() != chainCount)
            {
                throw new IllegalStateException("Templates have inconsistent chain sizes");
            }
        }
        for (FrameInfo t : templateInfo)
        {
            if (t.getTemporalId() > maxTemporalId)
            {
                throw new IllegalStateException("Incorrect maxTemporalId");
            }
        }
        if (!(maxRenderResolutions.isEmpty() || maxRenderResolutions.size() == maxSpatialId + 1))
        {
            throw new IllegalStateException("Non-zero number of render resolutions does not match maxSpatialId");
        }
        for (FrameInfo t : templateInfo)
        {
            if (t.getSpatialId() > maxSpatialId)
            {
                throw new IllegalStateException("Incorrect maxSpatialId");
            }
        }
    }

    public int getTemplateIdOffset()
    {
        return templateIdOffset;
    }

    public void setTemplateIdOffset(int templateIdOffset)
    {
        this.templateIdOffset = templateIdOffset;
    }

    public List<FrameInfo> getTemplateInfo()
    {
        return templateInfo;
    }

    public List<Integer> getDecodeTargetProtectedBy()
    {
        return decodeTargetProtectedBy;
    }

    public List<DecodeTargetLayer> getDecodeTargetLayers()
    {
        return decodeTargetLayers;
    }

    public List<Resolution> getMaxRenderResolutions()
    {
        return maxRenderResolutions;
    }

    public int getMaxSpatialId()
    {
        return maxSpatialId;
    }

    public int getMaxTemporalId()
    {
        return maxTemporalId;
    }

    public int getTemplateCount()
    {
        return templateInfo.size();
    }

    public int getDecodeTargetCount()
    {
        return decodeTargetLayers.size();
    }

    public int getChainCount()
    {
        return chainCount;
    }

    public int getUnpaddedLengthBits()
    {
        int length = 6; // Template ID offset

        length += 5; // DT Count - 1

        length += getTemplateCount() * 2; // templateLayers
        length += getTemplateCount() * getDecodeTargetCount() * 2; // TemplateDTIs
        for (FrameInfo t : templateInfo)
        {
            length += t.getFdiffCnt() * 5 + 1; // TemplateFDiffs
        }
        // TemplateChains
        length += nsBits(getDecodeTargetCount() + 1, chainCount);
        if (chainCount > 0)
        {
            for (int protectedBy : decodeTargetProtectedBy)
            {
                length += nsBits(chainCount, protectedBy);
            }
            length += getTemplateCount() * chainCount * 4;
        }
        length += 1; // ResolutionsPresent
        length += maxRenderResolutions.size() * 32; // RenderResolutions

        return length;
    }

    public Av1TemplateDependencyStructure clone()
    {
        return new Av1TemplateDependencyStructure(
            templateIdOffset,
            // These objects are not mutable so it's safe to copy them by reference
            templateInfo,
            decodeTargetProtectedBy,
            decodeTargetLayers,
            maxRenderResolutions,
            maxSpatialId,
            maxTemporalId
        );
    }

    public void write(BitWriter writer)
    {
        writer.writeBits(6, templateIdOffset);

        writer.writeBits(5, getDecodeTargetCount() - 1);

        writeTemplateLayers(writer);
        writeTemplateDtis(writer);
        writeTemplateFdiffs(writer);
        writeTemplateChains(writer);

        writeRenderResolutions(writer);
    }

    private void writeTemplateLayers(BitWriter writer)
    {
        if (!(templateInfo.get(0).getSpatialId() == 0 && templateInfo.get(0).getTemporalId() == 0))
        {
            throw new IllegalStateException(
                "First template must have spatial and temporal IDs 0/0, but found " +
                    templateInfo.get(0).getSpatialId() + "/" + templateInfo.get(0).getTemporalId()
            );
        }
        for (int templateNum = 1; templateNum < templateInfo.size(); templateNum++)
        {
            int layerIdc;
            FrameInfo cur = templateInfo.get(templateNum);
            FrameInfo prev = templateInfo.get(templateNum - 1);
            if (cur.getSpatialId() == prev.getSpatialId() && cur.getTemporalId() == prev.getTemporalId())
            {
                layerIdc = 0;
            }
            else if (cur.getSpatialId() == prev.getSpatialId() && cur.getTemporalId() == prev.getTemporalId() + 1)
            {
                layerIdc = 1;
            }
            else if (cur.getSpatialId() == prev.getSpatialId() + 1 && cur.getTemporalId() == 0)
            {
                layerIdc = 2;
            }
            else
            {
                throw new IllegalStateException(
                    "Template " + templateNum + " with spatial and temporal IDs " +
                        cur.getSpatialId() + "/" + cur.getTemporalId() +
                        " cannot follow template " + (templateNum - 1) + " with spatial and temporal IDs " +
                        prev.getSpatialId() + "/" + prev.getTemporalId() + "."
                );
            }
            writer.writeBits(2, layerIdc);
        }
        writer.writeBits(2, 3);
    }

    private void writeTemplateDtis(BitWriter writer)
    {
        for (FrameInfo t : templateInfo)
        {
            for (DTI dti : t.getDti())
            {
                writer.writeBits(2, dti.getDti());
            }
        }
    }

    private void writeTemplateFdiffs(BitWriter writer)
    {
        for (FrameInfo t : templateInfo)
        {
            for (int fdiff : t.getFdiff())
            {
                writer.writeBit(true);
                writer.writeBits(4, fdiff - 1);
            }
            writer.writeBit(false);
        }
    }

    private void writeTemplateChains(BitWriter writer)
    {
        writer.writeNs(getDecodeTargetCount() + 1, chainCount);
        for (int protectedBy : decodeTargetProtectedBy)
        {
            writer.writeNs(chainCount, protectedBy);
        }
        for (FrameInfo t : templateInfo)
        {
            for (int chain : t.getChains())
            {
                writer.writeBits(4, chain);
            }
        }
    }

    private void writeRenderResolutions(BitWriter writer)
    {
        if (maxRenderResolutions.isEmpty())
        {
            writer.writeBit(false);
        }
        else
        {
            writer.writeBit(true);
            for (Resolution r : maxRenderResolutions)
            {
                writer.writeBits(16, r.getWidth() - 1);
                writer.writeBits(16, r.getHeight() - 1);
            }
        }
    }

    /** Return whether, in this structure, it's possible to switch from DT {@code fromDt} to DT {@code toDt}
     * without a keyframe.
     * Note this makes certain assumptions about the encoding structure.
     */
    public boolean canSwitchWithoutKeyframe(int fromDt, int toDt)
    {
        for (FrameInfo t : templateInfo)
        {
            if (t.hasInterPictureDependency() && t.getDti().size() > fromDt && t.getDti().size() > toDt &&
                t.getDti().get(fromDt) != DTI.NOT_PRESENT && t.getDti().get(toDt) == DTI.SWITCH)
            {
                return true;
            }
        }
        return false;
    }

    /** Given that we are sending packets for a given DT, return a decodeTargetBitmask corresponding to all DTs
     * contained in that DT.
     */
    public int getDtBitmaskForDt(int dt)
    {
        int mask = (1 << getDecodeTargetCount()) - 1;
        for (FrameInfo frameInfo : templateInfo)
        {
            List<DTI> dtiList = frameInfo.getDti();
            for (int i = 0; i < dtiList.size(); i++)
            {
                DTI dti = dtiList.get(i);
                if (frameInfo.getDti().get(dt) == DTI.NOT_PRESENT && dti != DTI.NOT_PRESENT)
                {
                    mask = mask & ~(1 << i);
                }
            }
        }
        return mask;
    }

    @Override
    public String toString()
    {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("templateIdOffset", templateIdOffset);

        Map<Integer, String> templateInfoStrings = new LinkedHashMap<>();
        for (int i = 0; i < templateInfo.size(); i++)
        {
            templateInfoStrings.put(i, templateInfo.get(i).toString());
        }
        node.set("templateInfo", mapper.valueToTree(templateInfoStrings));

        node.set("decodeTargetProtectedBy", mapper.valueToTree(toIndexedMap(decodeTargetProtectedBy)));

        Map<Integer, String> decodeTargetLayerStrings = new LinkedHashMap<>();
        for (int i = 0; i < decodeTargetLayers.size(); i++)
        {
            decodeTargetLayerStrings.put(i, decodeTargetLayers.get(i).toString());
        }
        node.set("decodeTargetLayers", mapper.valueToTree(decodeTargetLayerStrings));

        if (!maxRenderResolutions.isEmpty())
        {
            Map<Integer, String> resolutionStrings = new LinkedHashMap<>();
            for (int i = 0; i < maxRenderResolutions.size(); i++)
            {
                resolutionStrings.put(i, maxRenderResolutions.get(i).toString());
            }
            node.set("maxRenderResolutions", mapper.valueToTree(resolutionStrings));
        }

        node.put("maxSpatialId", maxSpatialId);
        node.put("maxTemporalId", maxTemporalId);

        return node.toString();
    }

    private static <T> Map<Integer, T> toIndexedMap(List<T> list)
    {
        Map<Integer, T> map = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++)
        {
            map.put(i, list.get(i));
        }
        return map;
    }

    static int nsBits(int n, int v)
    {
        if (!(n > 0))
        {
            throw new IllegalArgumentException();
        }
        if (n == 1)
        {
            return 0;
        }
        int w = 0;
        int x = n;
        while (x != 0)
        {
            x = x >> 1;
            w++;
        }
        int m = (1 << w) - n;
        if (v < m)
        {
            return w - 1;
        }
        return w;
    }

    public static class DecodeTargetLayer
    {
        private final int spatialId;
        private final int temporalId;

        public DecodeTargetLayer(int spatialId, int temporalId)
        {
            this.spatialId = spatialId;
            this.temporalId = temporalId;
        }

        public int getSpatialId()
        {
            return spatialId;
        }

        public int getTemporalId()
        {
            return temporalId;
        }

        @Override
        public String toString()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("spatialId", spatialId);
            node.put("temporalId", temporalId);
            return node.toString();
        }
    }

    public static class Resolution
    {
        private final int width;
        private final int height;

        public Resolution(int width, int height)
        {
            this.width = width;
            this.height = height;
        }

        public int getWidth()
        {
            return width;
        }

        public int getHeight()
        {
            return height;
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof Resolution))
            {
                return false;
            }
            Resolution that = (Resolution) other;
            return width == that.width && height == that.height;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(width, height);
        }

        @Override
        public String toString()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("width", width);
            node.put("height", height);
            return node.toString();
        }
    }
}
