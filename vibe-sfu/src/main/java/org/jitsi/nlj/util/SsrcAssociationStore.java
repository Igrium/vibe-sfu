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

package org.jitsi.nlj.util;

import org.jitsi.nlj.rtp.SsrcAssociationType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SsrcAssociationStore
{
    private final List<SsrcAssociation> ssrcAssociations = new CopyOnWriteArrayList<>();

    /**
     * The SSRC associations indexed by the primary SSRC. Since an SSRC may have
     * multiple secondary SSRC mappings, the primary SSRC maps to a list of its
     * SSRC associations
     */
    private volatile Map<Long, List<SsrcAssociation>> ssrcAssociationsByPrimarySsrc = new HashMap<>();
    private volatile Map<Long, SsrcAssociation> ssrcAssociationsBySecondarySsrc = new HashMap<>();

    private final List<Consumer<SsrcAssociation>> handlers = new CopyOnWriteArrayList<>();

    /**
     * Each time an association is added, we want to invoke the handlers
     * and each time a handler is added, want to invoke it with all existing
     * associations. In order to make each of those operations a single,
     * atomic operation, we use this lock to synchronize them.
     */
    private final Object lock = new Object();

    public void addAssociation(SsrcAssociation ssrcAssociation)
    {
        synchronized (lock)
        {
            ssrcAssociations.add(ssrcAssociation);
            rebuildMaps();
            handlers.forEach(handler -> handler.accept(ssrcAssociation));
        }
    }

    private void rebuildMaps()
    {
        ssrcAssociationsByPrimarySsrc = ssrcAssociations.stream()
            .collect(Collectors.groupingBy(SsrcAssociation::getPrimarySsrc));
        ssrcAssociationsBySecondarySsrc = ssrcAssociations.stream()
            .collect(Collectors.toMap(SsrcAssociation::getSecondarySsrc, a -> a, (a, b) -> b));
    }

    public Long getPrimarySsrc(long secondarySsrc)
    {
        SsrcAssociation association = ssrcAssociationsBySecondarySsrc.get(secondarySsrc);
        return association == null ? null : association.getPrimarySsrc();
    }

    public Long getSecondarySsrc(long primarySsrc, SsrcAssociationType associationType)
    {
        List<SsrcAssociation> associations = ssrcAssociationsByPrimarySsrc.get(primarySsrc);
        if (associations == null)
        {
            return null;
        }
        return associations.stream()
            .filter(a -> a.getType() == associationType)
            .findFirst()
            .map(SsrcAssociation::getSecondarySsrc)
            .orElse(null);
    }

    /**
     * When an SSRC has no associations at all (audio, for example), we consider it a
     * 'primary' SSRC. So to perform this check we assume the given SSRC has been
     * signalled and simply verify that it's *not* signaled as a secondary SSRC.
     * Note that this may mean there is a slight window before the SSRC associations are
     * processed during which we return true for an SSRC which will later be denoted
     * as a secondary ssrc.
     */
    public boolean isPrimarySsrc(long ssrc)
    {
        return !ssrcAssociationsBySecondarySsrc.containsKey(ssrc);
    }

    public void onAssociation(Consumer<SsrcAssociation> handler)
    {
        synchronized (lock)
        {
            handlers.add(handler);
            ssrcAssociations.forEach(handler);
        }
    }

    @Override
    public String toString()
    {
        return ssrcAssociations.toString();
    }
}
