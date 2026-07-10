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

package org.jitsi.videobridge;

/**
 * Plain Java replacement for the upstream metaconfig-backed {@code TransportConfig}.
 * Holds the upstream default values (this library has no HOCON configuration system).
 */
public final class TransportConfig
{
    /**
     * The size of the per-connection outgoing packet queue
     * (upstream {@code videobridge.transport.send.queue-size}).
     */
    private static final int QUEUE_SIZE = 1024;

    private TransportConfig()
    {
    }

    public static int getQueueSize()
    {
        return QUEUE_SIZE;
    }
}
