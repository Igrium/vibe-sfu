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
package org.jitsi.videobridge.dcsctp;

import org.jitsi.dcsctp4j.DcSctpSocketCallbacks;
import org.jitsi.dcsctp4j.Timeout;
import org.jitsi.videobridge.util.TaskPools;

import java.lang.ref.WeakReference;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public abstract class DcSctpBaseCallbacks implements DcSctpSocketCallbacks
{
    public final DcSctpTransport transport;
    public final Clock clock;

    protected DcSctpBaseCallbacks(DcSctpTransport transport)
    {
        this(transport, Clock.systemUTC());
    }

    protected DcSctpBaseCallbacks(DcSctpTransport transport, Clock clock)
    {
        this.transport = transport;
        this.clock = clock;
    }

    /* Methods we can usefully implement for every JVB socket */
    @Override
    public Timeout createTimeout(DelayPrecision p0)
    {
        return new ATimeout(transport);
    }

    @Override
    public Instant Now()
    {
        return clock.instant();
    }

    @Override
    public long getRandomInt(long low, long high)
    {
        return ThreadLocalRandom.current().nextLong(low, high);
    }

    /* Methods we wouldn't normally expect to be called for a JVB SCTP socket. */
    @Override
    public void OnConnectionRestarted()
    {
        transport.logger.info("Surprising SCTP callback: connection restarted");
    }

    @Override
    public void OnStreamsResetFailed(short[] outgoingStreams, String reason)
    {
        transport.logger.info(
            "Surprising SCTP callback: streams " + Arrays.toString(outgoingStreams) + " reset failed: " + reason
        );
    }

    @Override
    public void OnStreamsResetPerformed(short[] outgoingStreams)
    {
        // This is normal following a call to close(), which is a hard-close (as opposed to shutdown() which is
        // soft-close)
        transport.logger.info("Outgoing streams " + Arrays.toString(outgoingStreams) + " reset");
    }

    @Override
    public void OnIncomingStreamsReset(short[] incomingStreams)
    {
        /* Does Chrome ever reset streams? */
        transport.logger.info(
            "Surprising SCTP callback: incoming streams " + Arrays.toString(incomingStreams) + " reset"
        );
    }

    private static class ATimeout implements Timeout
    {
        // This holds a weak reference to the transport, to break JNI reference cycles
        private final WeakReference<DcSctpTransport> weakTransport;
        private long timeoutId = 0;
        private ScheduledFuture<?> scheduledFuture;
        private Future<?> future;

        ATimeout(DcSctpTransport transport)
        {
            this.weakTransport = new WeakReference<>(transport);
        }

        private DcSctpTransport getTransport()
        {
            return weakTransport.get();
        }

        @Override
        public void start(long duration, long timeoutId)
        {
            try
            {
                this.timeoutId = timeoutId;
                scheduledFuture = TaskPools.SCHEDULED_POOL.schedule(() -> {
                    /* Execute it on the IO_POOL, because a timer may trigger sending new SCTP packets. */
                    future = TaskPools.IO_POOL.submit(() -> {
                        DcSctpTransport t = getTransport();
                        if (t != null)
                        {
                            t.handleTimeout(this.timeoutId);
                        }
                    });
                }, duration, TimeUnit.MILLISECONDS);
            }
            catch (Throwable e)
            {
                DcSctpTransport t = getTransport();
                if (t != null && t.logger != null)
                {
                    t.logger.warn("Exception scheduling DCSCTP timeout", e);
                }
            }
        }

        @Override
        public void stop()
        {
            if (scheduledFuture != null)
            {
                scheduledFuture.cancel(false);
            }
            if (future != null)
            {
                future.cancel(false);
            }
            scheduledFuture = null;
            future = null;
        }
    }
}
