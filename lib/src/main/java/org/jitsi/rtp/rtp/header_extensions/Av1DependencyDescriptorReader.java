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

import org.jitsi.rtp.rtp.RtpPacket;
import org.jitsi.rtp.util.BitReader;

import java.util.ArrayList;
import java.util.List;

public class Av1DependencyDescriptorReader
{
    private final int length;

    private boolean startOfFrame = false;
    private boolean endOfFrame = false;
    private int frameDependencyTemplateId = 0;
    private int frameNumber = 0;

    private List<DTI> customDtis = null;
    private List<Integer> customFdiffs = null;
    private List<Integer> customChains = null;

    private Av1TemplateDependencyStructure localTemplateDependencyStructure = null;
    private Av1TemplateDependencyStructure templateDependencyStructure = null;

    private Integer activeDecodeTargetsBitmask = null;

    private final BitReader reader;

    public Av1DependencyDescriptorReader(byte[] buffer, int offset, int length)
    {
        this.length = length;
        this.reader = new BitReader(buffer, offset, length);
    }

    public Av1DependencyDescriptorReader(RtpPacket.HeaderExtension ext)
    {
        this(ext.getBuffer(), ext.getDataOffset(), ext.getDataLengthBytes());
    }

    /** Parse those parts of the dependency descriptor that can be parsed statelessly, i.e. without an external
     * template dependency structure.  The returned object will not be a complete representation of the
     * dependency descriptor, because some fields need the external structure to be parseable.
     */
    public Av1DependencyDescriptorStatelessSubset parseStateless()
    {
        reset();
        readMandatoryDescriptorFields();

        if (length > 3)
        {
            boolean templateDependencyStructurePresent = reader.bitAsBoolean();

            /* activeDecodeTargetsPresent, customDtisFlag, customFdiffsFlag, and customChainsFlag;
             * none of these fields are parseable statelessly.
             */
            reader.skipBits(4);

            if (templateDependencyStructurePresent)
            {
                localTemplateDependencyStructure = readTemplateDependencyStructure();
            }
        }
        return new Av1DependencyDescriptorStatelessSubset(
            startOfFrame,
            endOfFrame,
            frameDependencyTemplateId,
            frameNumber,
            localTemplateDependencyStructure
        );
    }

    /** Parse the dependency descriptor in the context of {@code dep}, the currently-applicable template dependency
     * structure.*/
    public Av1DependencyDescriptorHeaderExtension parse(Av1TemplateDependencyStructure dep)
    {
        reset();
        readMandatoryDescriptorFields();
        if (length > 3)
        {
            readExtendedDescriptorFields(dep);
        }
        else
        {
            if (dep == null)
            {
                throw new Av1DependencyException("No external dependency structure specified for non-first packet");
            }
            templateDependencyStructure = dep;
        }
        return new Av1DependencyDescriptorHeaderExtension(
            startOfFrame,
            endOfFrame,
            frameDependencyTemplateId,
            frameNumber,
            localTemplateDependencyStructure,
            activeDecodeTargetsBitmask,
            customDtis,
            customFdiffs,
            customChains,
            templateDependencyStructure
        );
    }

    private void reset()
    {
        reader.reset();
    }

    private void readMandatoryDescriptorFields()
    {
        startOfFrame = reader.bitAsBoolean();
        endOfFrame = reader.bitAsBoolean();
        frameDependencyTemplateId = reader.bits(6);
        frameNumber = reader.bits(16);
    }

    private void readExtendedDescriptorFields(Av1TemplateDependencyStructure dep)
    {
        boolean templateDependencyStructurePresent = reader.bitAsBoolean();
        boolean activeDecodeTargetsPresent = reader.bitAsBoolean();
        boolean customDtisFlag = reader.bitAsBoolean();
        boolean customFdiffsFlag = reader.bitAsBoolean();
        boolean customChainsFlag = reader.bitAsBoolean();

        if (templateDependencyStructurePresent)
        {
            localTemplateDependencyStructure = readTemplateDependencyStructure();
            templateDependencyStructure = localTemplateDependencyStructure;
        }
        else
        {
            if (dep == null)
            {
                throw new Av1DependencyException("No external dependency structure specified for non-first packet");
            }
            templateDependencyStructure = dep;
        }
        if (activeDecodeTargetsPresent)
        {
            activeDecodeTargetsBitmask = reader.bits(templateDependencyStructure.getDecodeTargetCount());
        }
        else if (templateDependencyStructurePresent)
        {
            activeDecodeTargetsBitmask = (1 << templateDependencyStructure.getDecodeTargetCount()) - 1;
        }

        if (customDtisFlag)
        {
            customDtis = readFrameDtis();
        }
        if (customFdiffsFlag)
        {
            customFdiffs = readFrameFdiffs();
        }
        if (customChainsFlag)
        {
            customChains = readFrameChains();
        }
    }

    /* Data for template dependency structure */
    private int templateIdOffset = 0;
    private final List<TemplateFrameInfo> templateInfo = new ArrayList<>();
    private final List<Integer> decodeTargetProtectedBy = new ArrayList<>();
    private final List<Av1TemplateDependencyStructure.DecodeTargetLayer> decodeTargetLayers = new ArrayList<>();
    private final List<Av1TemplateDependencyStructure.Resolution> maxRenderResolutions = new ArrayList<>();

    private int dtCnt = 0;

    private void resetDependencyStructureInfo()
    {
        /* These fields are assembled incrementally when parsing a dependency structure; reset them
         * in case we're running a parser more than once.
         */
        templateCnt = 0;
        templateInfo.clear();
        decodeTargetProtectedBy.clear();
        decodeTargetLayers.clear();
        maxRenderResolutions.clear();
    }

    private Av1TemplateDependencyStructure readTemplateDependencyStructure()
    {
        resetDependencyStructureInfo();

        templateIdOffset = reader.bits(6);

        int dtCntMinusOne = reader.bits(5);
        dtCnt = dtCntMinusOne + 1;

        readTemplateLayers();
        readTemplateDtis();
        readTemplateFdiffs();
        readTemplateChains();
        readDecodeTargetLayers();

        boolean resolutionsPresent = reader.bitAsBoolean();

        if (resolutionsPresent)
        {
            readRenderResolutions();
        }

        return new Av1TemplateDependencyStructure(
            templateIdOffset,
            new ArrayList<>(templateInfo),
            new ArrayList<>(decodeTargetProtectedBy),
            new ArrayList<>(decodeTargetLayers),
            new ArrayList<>(maxRenderResolutions),
            maxSpatialId,
            maxTemporalId
        );
    }

    private int templateCnt = 0;
    private int maxSpatialId = 0;
    private int maxTemporalId = 0;

    private void readTemplateLayers()
    {
        int temporalId = 0;
        int spatialId = 0;

        int nextLayerIdc;
        do
        {
            templateInfo.add(templateCnt, new TemplateFrameInfo(spatialId, temporalId));
            templateCnt++;
            nextLayerIdc = reader.bits(2);
            if (nextLayerIdc == 1)
            {
                temporalId++;
                if (maxTemporalId < temporalId)
                {
                    maxTemporalId = temporalId;
                }
            }
            else if (nextLayerIdc == 2)
            {
                temporalId = 0;
                spatialId++;
            }
        }
        while (nextLayerIdc != 3);

        if (!(templateInfo.size() == templateCnt))
        {
            throw new IllegalStateException("templateInfo.size() != templateCnt");
        }

        maxSpatialId = spatialId;
    }

    private void readTemplateDtis()
    {
        for (int templateIndex = 0; templateIndex < templateCnt; templateIndex++)
        {
            for (int dtIndex = 0; dtIndex < dtCnt; dtIndex++)
            {
                templateInfo.get(templateIndex).getDti().add(dtIndex, DTI.fromInt(reader.bits(2)));
            }
        }
    }

    private List<DTI> readFrameDtis()
    {
        int count = templateDependencyStructure.getDecodeTargetCount();
        List<DTI> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            result.add(DTI.fromInt(reader.bits(2)));
        }
        return result;
    }

    private void readTemplateFdiffs()
    {
        for (int templateIndex = 0; templateIndex < templateCnt; templateIndex++)
        {
            int fdiffCnt = 0;
            boolean fdiffFollowsFlag = reader.bitAsBoolean();
            while (fdiffFollowsFlag)
            {
                int fdiffMinusOne = reader.bits(4);
                templateInfo.get(templateIndex).getFdiff().add(fdiffCnt, fdiffMinusOne + 1);
                fdiffCnt++;
                fdiffFollowsFlag = reader.bitAsBoolean();
            }
            if (!(fdiffCnt == templateInfo.get(templateIndex).getFdiffCnt()))
            {
                throw new IllegalStateException("fdiffCnt != templateInfo[templateIndex].fdiffCnt");
            }
        }
    }

    private List<Integer> readFrameFdiffs()
    {
        List<Integer> result = new ArrayList<>();
        int nextFdiffSize = reader.bits(2);
        while (nextFdiffSize != 0)
        {
            int fdiffMinus1 = reader.bits(4 * nextFdiffSize);
            result.add(fdiffMinus1 + 1);
            nextFdiffSize = reader.bits(2);
        }
        return result;
    }

    private void readTemplateChains()
    {
        int chainCount = reader.ns(dtCnt + 1);
        if (chainCount != 0)
        {
            for (int dtIndex = 0; dtIndex < dtCnt; dtIndex++)
            {
                decodeTargetProtectedBy.add(dtIndex, reader.ns(chainCount));
            }
            for (int templateIndex = 0; templateIndex < templateCnt; templateIndex++)
            {
                for (int chainIndex = 0; chainIndex < chainCount; chainIndex++)
                {
                    templateInfo.get(templateIndex).getChains().add(chainIndex, reader.bits(4));
                }
                if (!(templateInfo.get(templateIndex).getChains().size() == chainCount))
                {
                    throw new IllegalStateException("templateInfo[templateIndex].chains.size() != chainCount");
                }
            }
        }
    }

    private List<Integer> readFrameChains()
    {
        int count = templateDependencyStructure.getChainCount();
        List<Integer> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            result.add(reader.bits(8));
        }
        return result;
    }

    private void readDecodeTargetLayers()
    {
        for (int dtIndex = 0; dtIndex < dtCnt; dtIndex++)
        {
            int spatialId = 0;
            int temporalId = 0;
            for (int templateIndex = 0; templateIndex < templateCnt; templateIndex++)
            {
                if (templateInfo.get(templateIndex).getDti().get(dtIndex) != DTI.NOT_PRESENT)
                {
                    if (templateInfo.get(templateIndex).getSpatialId() > spatialId)
                    {
                        spatialId = templateInfo.get(templateIndex).getSpatialId();
                    }
                    if (templateInfo.get(templateIndex).getTemporalId() > temporalId)
                    {
                        temporalId = templateInfo.get(templateIndex).getTemporalId();
                    }
                }
            }
            decodeTargetLayers.add(dtIndex, new Av1TemplateDependencyStructure.DecodeTargetLayer(spatialId, temporalId));
        }
        if (!(decodeTargetLayers.size() == dtCnt))
        {
            throw new IllegalStateException("decodeTargetLayers.size() != dtCnt");
        }
    }

    private void readRenderResolutions()
    {
        for (int spatialId = 0; spatialId <= maxSpatialId; spatialId++)
        {
            int widthMinus1 = reader.bits(16);
            int heightMinus1 = reader.bits(16);
            maxRenderResolutions.add(
                spatialId, new Av1TemplateDependencyStructure.Resolution(widthMinus1 + 1, heightMinus1 + 1));
        }
    }
}
