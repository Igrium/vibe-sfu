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

package org.jitsi.nlj;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Maintains an array of {@link MediaSourceDesc}. The set method preserves the existing sources that match one of
 * the new sources, because {@link MediaSourceDesc} holds some local state (rate statistics) that we would like to
 * keep. Ideally this state should be out of the source descriptor, in which case this class would be obsolete.
 *
 * @author Boris Grozev
 */
public class MediaSources
{
    private MediaSourceDesc[] sources = new MediaSourceDesc[0];

    public boolean setMediaSources(MediaSourceDesc[] newSources)
    {
        MediaSourceDesc[] oldSources = sources;

        if (oldSources.length == 0 || newSources.length == 0)
        {
            sources = newSources;
            return oldSources.length != newSources.length;
        }

        int cntMatched = 0;
        MediaSourceDesc[] mergedSources = new MediaSourceDesc[newSources.length];
        for (int i = 0; i < newSources.length; i++)
        {
            long newPrimarySSRC = newSources[i].getPrimarySSRC();
            MediaSourceDesc matched = null;
            for (MediaSourceDesc oldSource : oldSources)
            {
                if (oldSource.matches(newPrimarySSRC))
                {
                    cntMatched++;
                    // NOTE: we deliberately do not update the old source instance
                    // with the encodings of the new one.  Values set on the
                    // source are more likely to be correct than ones generated
                    // from signaling about the source.  (Revisit this if we
                    // encounter a scenario where this isn't true...)
                    matched = oldSource;
                    break;
                }
            }
            mergedSources[i] = matched != null ? matched : newSources[i];
        }

        sources = mergedSources;
        return oldSources.length != newSources.length || cntMatched != oldSources.length;
    }

    public MediaSourceDesc[] getMediaSources()
    {
        return sources;
    }

    public ObjectNode debugState()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (MediaSourceDesc source : sources)
        {
            ObjectNode sourceNode = JsonNodeFactory.instance.objectNode();
            sourceNode.put("owner", source.getOwner());
            sourceNode.put("video_type", source.getVideoType().toString());
            for (RtpEncodingDesc encoding : source.getRtpEncodings())
            {
                sourceNode.set("rtp_encoding_" + encoding.getPrimarySSRC(), encoding.debugState());
            }
            node.set(source.getSourceName(), sourceNode);
        }
        return node;
    }
}
