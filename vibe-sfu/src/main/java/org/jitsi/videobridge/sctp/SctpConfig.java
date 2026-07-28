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

package org.jitsi.videobridge.sctp;

/**
 * Plain Java replacement for the upstream metaconfig-backed {@code SctpConfig}. Holds the
 * upstream default values as final fields (this library has no HOCON configuration system).
 */
public class SctpConfig
{
    public static final SctpConfig config = new SctpConfig();

    /** Whether SCTP should be signaled or used when signaled to us */
    private final boolean enabled = true;

    /**
     * Maximum number of data channels per connection. Upstream default is 1; unlike upstream
     * this is settable, because library hosts may want more than the single channel the
     * videobridge itself uses.
     */
    private volatile int maxChannels = 1;

    private SctpConfig()
    {
    }

    public boolean enabled()
    {
        return enabled;
    }

    public int getMaxChannels()
    {
        return maxChannels;
    }

    public void setMaxChannels(int maxChannels)
    {
        this.maxChannels = maxChannels;
    }
}
