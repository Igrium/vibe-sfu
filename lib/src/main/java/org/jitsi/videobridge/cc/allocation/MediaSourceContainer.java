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

import org.jitsi.nlj.MediaSourceDesc;

/**
 * Abstracts a media source for the purposes of {@link BandwidthAllocator}.
 *
 * (Library note: upstream declares this as a second top-level type in {@code BitrateController.kt}. Java forbids
 * more than one public top-level type per file, so it is split into its own compilation unit here; the package and
 * qualified name ({@code org.jitsi.videobridge.cc.allocation.MediaSourceContainer}) are unchanged. This is the seam
 * through which the (stripped) host Endpoint/relay abstraction plugs into the allocator: the host implements this
 * interface for whatever it uses to represent a remote participant.)
 */
public interface MediaSourceContainer
{
    String getId();

    MediaSourceDesc[] getMediaSources();
}
