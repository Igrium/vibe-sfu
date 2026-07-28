/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import java.util.Collection;
import java.util.Collections;

/**
 * (Library note: upstream declares this as a second top-level type in {@code BitrateController.kt}; split into its
 * own compilation unit for the same reason as {@link MediaSourceContainer}. Deviation: upstream is a Kotlin
 * {@code data class}; ported as a plain immutable holder.)
 */
public class BitrateControllerStatusSnapshot
{
    private final long currentTargetBps;
    private final long currentIdealBps;
    private final Collection<Long> activeSsrcs;
    private final boolean hasNonIdealLayer;

    public BitrateControllerStatusSnapshot(
        long currentTargetBps,
        long currentIdealBps,
        Collection<Long> activeSsrcs,
        boolean hasNonIdealLayer)
    {
        this.currentTargetBps = currentTargetBps;
        this.currentIdealBps = currentIdealBps;
        this.activeSsrcs = activeSsrcs;
        this.hasNonIdealLayer = hasNonIdealLayer;
    }

    public BitrateControllerStatusSnapshot(boolean hasNonIdealLayer)
    {
        this(-1L, -1L, Collections.emptyList(), hasNonIdealLayer);
    }

    public long getCurrentTargetBps()
    {
        return currentTargetBps;
    }

    public long getCurrentIdealBps()
    {
        return currentIdealBps;
    }

    public Collection<Long> getActiveSsrcs()
    {
        return activeSsrcs;
    }

    public boolean isHasNonIdealLayer()
    {
        return hasNonIdealLayer;
    }
}
