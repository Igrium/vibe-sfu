/*
 * Copyright @ 2020 - present 8x8, Inc.
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
package org.jitsi.videobridge.cc.allocation;

import org.jitsi.nlj.MediaSourceDesc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * (Deviation: upstream is a Kotlin file of top-level functions; ported as a holder of static methods, since Java
 * has no top-level functions.)
 */
public class Prioritize
{
    /**
     * (Deviation: upstream reads {@code videobridge.ssrc-limit.video} via the (stripped, conference-scoped)
     * {@code SsrcLimitConfig}. Plain constant with the upstream {@code reference.conf} default instead.)
     */
    private static final int MAX_VIDEO_SSRCS = 50;

    private Prioritize()
    {
    }

    /**
     * @param conferenceSources All available sources ordered by their endpoint's speech activity and video
     * availability. Note that when an endpoint has multiple sources and one of them is disabled it will still sort
     * high because the endpoint has video availability.
     */
    public static List<MediaSourceDesc> prioritize(
        List<MediaSourceDesc> conferenceSources,
        List<String> selectedSourceNames)
    {
        List<MediaSourceDesc> enabledSelectedSources = new ArrayList<>();
        List<MediaSourceDesc> enabledNonSelectedSources = new ArrayList<>();
        List<MediaSourceDesc> disabledSources = new ArrayList<>();

        // conferenceSources can be large, while selectedSourceNames is usually small, so do a single pass over
        // conferenceSources.
        for (MediaSourceDesc source : conferenceSources)
        {
            if (source.getVideoType().isEnabled())
            {
                if (selectedSourceNames.contains(source.getSourceName()))
                {
                    enabledSelectedSources.add(source);
                }
                else
                {
                    enabledNonSelectedSources.add(source);
                }
            }
            else
            {
                disabledSources.add(source);
            }
        }
        // The enabled selected sources are sorted according to the order in which they are selected and
        // prioritized over non-selected.
        enabledSelectedSources.sort((a, b) -> Integer.compare(
            selectedSourceNames.indexOf(a.getSourceName()), selectedSourceNames.indexOf(b.getSourceName())));
        enabledSelectedSources.addAll(enabledNonSelectedSources);
        // All disabled sources are sorted last, regardless of whether they are selected.
        enabledSelectedSources.addAll(disabledSources);

        return enabledSelectedSources;
    }

    public static List<MediaSourceDesc> prioritize(List<MediaSourceDesc> conferenceSources)
    {
        return prioritize(conferenceSources, Collections.emptyList());
    }

    /**
     * Return the "effective" constraints for the given media sources, i.e. the constraints adjusted for LastN.
     */
    public static Map<MediaSourceDesc, VideoConstraints> getEffectiveConstraints(
        List<MediaSourceDesc> sources,
        AllocationSettings allocationSettings)
    {
        // FIXME figure out before merge - is using source count instead of endpoints
        // Add 1 for the receiver endpoint, which is not in the list.
        int effectiveLastN = effectiveLastN(allocationSettings.getLastN(), sources.size() + 1);

        // Keep track of the number of sources with non-zero constraints. Once [effectiveLastN] of them have been
        // added, all other sources have effectiveConstraints 0, because they would never be forwarded by the
        // algorithm.
        int[] sourcesWithNonZeroConstraints = { 0 };

        Map<MediaSourceDesc, VideoConstraints> result = new HashMap<>();
        for (MediaSourceDesc source : sources)
        {
            VideoConstraints constraints;
            if (!source.getVideoType().isEnabled() || sourcesWithNonZeroConstraints[0] >= effectiveLastN)
            {
                constraints = VideoConstraints.NOTHING;
            }
            else
            {
                constraints = allocationSettings.getConstraints(source.getSourceName());
                if (!constraints.isDisabled())
                {
                    sourcesWithNonZeroConstraints[0]++;
                }
            }
            result.put(source, constraints);
        }
        return result;
    }

    /**
     * The LastN value adjusted according to the limits configured on the bridge, or {@link Integer#MAX_VALUE} if
     * LastN is disabled.
     *
     * (Deviation: upstream also folds in a JVB-wide last-n limit ({@code jvbLastNSingleton}) and a conference-size
     * based last-n limit ({@code ConferenceSizeLastNLimits}), both of which come from the (stripped)
     * conference-wide state that doesn't exist in this per-peer library; both default to "no limit" in
     * {@code reference.conf}, so dropping them preserves default behavior.
     * TODO(port): if the host wants a JVB-wide or conference-size-based last-n cap, thread it into this method
     * (e.g. as additional parameters) the way upstream's singletons did.)
     */
    private static int effectiveLastN(int lastN, int conferenceSize)
    {
        int adjustedLastN = calculateLastN(lastN, MAX_VIDEO_SSRCS);
        return adjustedLastN < 0 ? Integer.MAX_VALUE : adjustedLastN;
    }

    /**
     * (Ported from {@code JvbLastN.kt}'s top-level {@code calculateLastN(vararg lastN: Int)} function, which is
     * otherwise unused now that the conference-wide callers of it are stripped.)
     */
    private static int calculateLastN(int... lastN)
    {
        int min = Integer.MAX_VALUE;
        for (int value : lastN)
        {
            int normalized = value == -1 ? Integer.MAX_VALUE : value;
            if (normalized < min)
            {
                min = normalized;
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }
}
