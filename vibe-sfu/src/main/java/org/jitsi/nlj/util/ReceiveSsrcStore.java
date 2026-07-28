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
package org.jitsi.nlj.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.utils.MediaType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class ReceiveSsrcStore
{
    private final SsrcAssociationStore ssrcAssociationStore;

    // NOTE: to enable efficient lookup for various use cases, we store
    // different 'views' of the receive SSRCs in multiple data structures
    // (below). Updates to these data structures do not happen atomically,
    // so it's possible the view across them may be inconsistent while changes
    // are propagating. We don't currently have code that cares about this,
    // so it's done this way to avoid having to synchronize updates and access
    // across all of them.
    /**
     * All signaled receive SSRCs
     */
    private final Set<Long> receiveSsrcs = new CopyOnWriteArraySet<>();

    /**
     * All receive SSRCs indexed by their media type
     */
    private final Map<MediaType, Set<Long>> receiveSsrcsByMediaType = new ConcurrentHashMap<>();

    /**
     * 'Primary' receive media SSRCs (excludes things like RTX)
     */
    private final Set<Long> primaryMediaSsrcs = new CopyOnWriteArraySet<>();

    /**
     * 'Primary' *video* SSRCs
     */
    private final Set<Long> primaryVideoSsrcs = new CopyOnWriteArraySet<>();

    public ReceiveSsrcStore(SsrcAssociationStore ssrcAssociationStore)
    {
        this.ssrcAssociationStore = ssrcAssociationStore;
        ssrcAssociationStore.onAssociation(this::onSsrcAssociation);
    }

    public Set<Long> getReceiveSsrcs()
    {
        return receiveSsrcs;
    }

    public Set<Long> getPrimaryMediaSsrcs()
    {
        return primaryMediaSsrcs;
    }

    public Set<Long> getPrimaryVideoSsrcs()
    {
        return primaryVideoSsrcs;
    }

    public void addReceiveSsrc(long ssrc, MediaType mediaType)
    {
        receiveSsrcsByMediaType.computeIfAbsent(mediaType, k -> new CopyOnWriteArraySet<>()).add(ssrc);
        receiveSsrcs.add(ssrc);
        if (ssrcAssociationStore.isPrimarySsrc(ssrc))
        {
            primaryMediaSsrcs.add(ssrc);
            if (mediaType == MediaType.VIDEO)
            {
                primaryVideoSsrcs.add(ssrc);
            }
        }
    }

    public void removeReceiveSsrc(long ssrc)
    {
        receiveSsrcs.remove(ssrc);
        receiveSsrcsByMediaType.values().forEach(set -> set.remove(ssrc));
        primaryMediaSsrcs.remove(ssrc);
        primaryVideoSsrcs.remove(ssrc);
    }

    /**
     * Handle new {@link SsrcAssociation}s
     */
    private void onSsrcAssociation(SsrcAssociation ssrcAssociation)
    {
        // Secondary SSRCs in associations shouldn't be considered
        // primary media or video SSRCs
        primaryVideoSsrcs.remove(ssrcAssociation.getSecondarySsrc());
        primaryMediaSsrcs.remove(ssrcAssociation.getSecondarySsrc());
    }

    public ObjectNode debugState()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> byMediaTypeAsStrings = new HashMap<>();
        receiveSsrcsByMediaType.forEach((mediaType, ssrcs) -> byMediaTypeAsStrings.put(mediaType.toString(), ssrcs.toString()));
        node.set("receive_ssrcs", mapper.valueToTree(byMediaTypeAsStrings));
        node.set("primary_media_ssrcs", mapper.valueToTree(primaryMediaSsrcs));
        node.set("primary_video_ssrcs", mapper.valueToTree(primaryVideoSsrcs));
        return node;
    }
}
