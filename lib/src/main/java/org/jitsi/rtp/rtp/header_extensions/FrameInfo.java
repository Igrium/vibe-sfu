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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FrameInfo
{
    private final int spatialId;
    private final int temporalId;
    private final List<DTI> dti;
    private final List<Integer> fdiff;
    private final List<Integer> chains;

    public FrameInfo(int spatialId, int temporalId, List<DTI> dti, List<Integer> fdiff, List<Integer> chains)
    {
        this.spatialId = spatialId;
        this.temporalId = temporalId;
        this.dti = dti;
        this.fdiff = fdiff;
        this.chains = chains;
    }

    public int getSpatialId()
    {
        return spatialId;
    }

    public int getTemporalId()
    {
        return temporalId;
    }

    public List<DTI> getDti()
    {
        return dti;
    }

    public List<Integer> getFdiff()
    {
        return fdiff;
    }

    public List<Integer> getChains()
    {
        return chains;
    }

    public int getFdiffCnt()
    {
        return fdiff.size();
    }

    @Override
    public boolean equals(Object other)
    {
        if (!(other instanceof FrameInfo))
        {
            return false;
        }
        FrameInfo that = (FrameInfo) other;
        return that.spatialId == spatialId &&
            that.temporalId == temporalId &&
            that.dti.equals(dti) &&
            that.fdiff.equals(fdiff) &&
            that.chains.equals(chains);
    }

    @Override
    public int hashCode()
    {
        int result = spatialId;
        result = 31 * result + temporalId;
        result = 31 * result + dti.hashCode();
        result = 31 * result + fdiff.hashCode();
        result = 31 * result + chains.hashCode();
        return result;
    }

    @Override
    public String toString()
    {
        return "spatialId=" + spatialId + ", temporalId=" + temporalId + ", dti=" + dti +
            ", fdiff=" + fdiff + ", chains=" + chains;
    }

    public String toJson()
    {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("spatialId", spatialId);
        node.put("temporalId", temporalId);
        List<String> dtiNames = new ArrayList<>();
        for (DTI d : dti)
        {
            dtiNames.add(d.name());
        }
        node.set("dti", mapper.valueToTree(dtiNames));
        node.set("fdiff", (ArrayNode) mapper.valueToTree(fdiff));
        node.set("chains", (ArrayNode) mapper.valueToTree(chains));
        return node.toString();
    }

    /** Whether the frame has a dependency on a frame earlier than this "picture", the other frames of this
     * temporal moment.  If it doesn't, it's probably part of a keyframe, and not part of the regular structure.
     * Note this makes assumptions about the scalability structure.
     */
    public boolean hasInterPictureDependency()
    {
        for (int f : fdiff)
        {
            if (f > spatialId)
            {
                return true;
            }
        }
        return false;
    }

    public List<Integer> getDtisPresent()
    {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < dti.size(); i++)
        {
            if (dti.get(i) != DTI.NOT_PRESENT)
            {
                result.add(i);
            }
        }
        return result;
    }
}
