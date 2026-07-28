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
package org.jitsi.nlj.rtcp;

import org.jitsi.rtp.rtcp.RtcpHeaderBuilder;
import org.jitsi.rtp.rtcp.RtcpPacket;
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacketBuilder;
import org.jitsi.rtp.util.RtpUtils;
import org.jitsi.utils.logging2.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RetransmissionRequester
{
    private static final int MAX_REQUESTS = 10;
    private static final Duration REQUEST_INTERVAL = Duration.ofMillis(150);

    private final Consumer<RtcpPacket> rtcpSender;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;
    private final Clock clock;
    private final Map<Long, StreamPacketRequester> streamPacketRequesters = new HashMap<>();

    public RetransmissionRequester(Consumer<RtcpPacket> rtcpSender, ScheduledExecutorService scheduler, Logger parentLogger)
    {
        this(rtcpSender, scheduler, parentLogger, Clock.systemUTC());
    }

    public RetransmissionRequester(
        Consumer<RtcpPacket> rtcpSender,
        ScheduledExecutorService scheduler,
        Logger parentLogger,
        Clock clock)
    {
        this.rtcpSender = rtcpSender;
        this.scheduler = scheduler;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        this.clock = clock;
    }

    public void packetReceived(long ssrc, int seqNum)
    {
        StreamPacketRequester streamPacketRequester;
        synchronized (streamPacketRequesters)
        {
            streamPacketRequester = streamPacketRequesters.computeIfAbsent(
                ssrc,
                key -> new StreamPacketRequester(key, scheduler, clock, rtcpSender, logger));
        }
        streamPacketRequester.packetReceived(seqNum);
    }

    public void stop()
    {
        synchronized (streamPacketRequesters)
        {
            streamPacketRequesters.values().forEach(StreamPacketRequester::stop);
            streamPacketRequesters.clear();
        }
    }

    /**
     * Manages retransmission requests for all packets for a specific SSRC
     */
    public static class StreamPacketRequester
    {
        private static final Instant NO_REQUEST_DUE = Instant.MAX;
        private static final int DEFAULT_MAX_MISSING_SEQ_NUMS = 100;

        private final long ssrc;
        private final ScheduledExecutorService scheduler;
        private final Clock clock;
        private final Consumer<RtcpPacket> rtcpSender;
        private final int maxMissingSeqNums;
        private final Logger logger;

        private final AtomicBoolean running = new AtomicBoolean(true);
        private int highestReceivedSeqNum = -1;
        private final Map<Integer, PacketRetransmissionRequest> requests = new HashMap<>();
        private final Object taskHandleLock = new Object();
        private ScheduledFuture<?> currentTaskHandle;

        public StreamPacketRequester(
            long ssrc,
            ScheduledExecutorService scheduler,
            Clock clock,
            Consumer<RtcpPacket> rtcpSender,
            Logger parentLogger)
        {
            this(ssrc, scheduler, clock, rtcpSender, parentLogger, DEFAULT_MAX_MISSING_SEQ_NUMS);
        }

        public StreamPacketRequester(
            long ssrc,
            ScheduledExecutorService scheduler,
            Clock clock,
            Consumer<RtcpPacket> rtcpSender,
            Logger parentLogger,
            int maxMissingSeqNums)
        {
            this.ssrc = ssrc;
            this.scheduler = scheduler;
            this.clock = clock;
            this.rtcpSender = rtcpSender;
            this.maxMissingSeqNums = maxMissingSeqNums;
            this.logger = parentLogger.createChildLogger(getClass().getName(), Map.of("ssrc", String.valueOf(ssrc)));
        }

        public void packetReceived(int seqNum)
        {
            if (highestReceivedSeqNum == -1)
            {
                highestReceivedSeqNum = seqNum;
                return;
            }
            synchronized (requests)
            {
                if (seqNum == highestReceivedSeqNum)
                {
                    logger.debug(() -> ssrc + " packet " + seqNum + " was received, currently missing " + getMissingSeqNums());
                    // By definition we've already received highestReceivedSeqNum, so nothing needs to be done.
                }
                else if (RtpUtils.isOlderThan(seqNum, highestReceivedSeqNum))
                {
                    logger.debug(() -> ssrc + " packet " + seqNum + " was received, currently missing " + getMissingSeqNums());
                    // An older packet, possibly already requested
                    requests.remove(seqNum);
                    if (requests.isEmpty())
                    {
                        logger.debug(() -> ssrc + " no more missing seq nums, cancelling pending work");
                        updateWorkDueTime(NO_REQUEST_DUE);
                    }
                }
                else if (RtpUtils.isNextAfter(seqNum, highestReceivedSeqNum))
                {
                    highestReceivedSeqNum = seqNum;
                }
                else if (RtpUtils.numPacketsTo(highestReceivedSeqNum, seqNum) < maxMissingSeqNums)
                {
                    logger.debug(() -> ssrc + " missing packet detected! Just received " +
                        seqNum + ", last received was " + highestReceivedSeqNum);
                    for (int missingSeqNum : RtpUtils.sequenceNumbersBetween(highestReceivedSeqNum, seqNum))
                    {
                        PacketRetransmissionRequest request = new PacketRetransmissionRequest(missingSeqNum);
                        requests.put(missingSeqNum, request);
                        updateWorkDueTime(clock.instant());
                    }
                    highestReceivedSeqNum = seqNum;
                }
                else
                {
                    // diff > maxMissingSeqNums
                    logger.warn(() -> ssrc + " large jump in sequence numbers detected (highest received was " +
                        highestReceivedSeqNum + ", current is " + seqNum + ", jump of " +
                        RtpUtils.numPacketsTo(highestReceivedSeqNum, seqNum) + ") , not requesting retransmissions");
                    highestReceivedSeqNum = seqNum;
                    // Reset and clear any pending work to do for this source
                    requests.clear();
                    logger.debug(() -> ssrc + " large packet gap, resetting and clearing all work");
                    updateWorkDueTime(NO_REQUEST_DUE);
                }
            }
        }

        public void stop()
        {
            running.set(false);
            synchronized (taskHandleLock)
            {
                if (currentTaskHandle != null)
                {
                    currentTaskHandle.cancel(false);
                }
            }
            synchronized (requests)
            {
                requests.clear();
            }
        }

        private void updateWorkDueTime(Instant newWorkDueTs)
        {
            logger.debug(() -> ssrc + " updating next work due time to " + newWorkDueTs);
            synchronized (taskHandleLock)
            {
                if (!running.get())
                {
                    logger.debug(() -> ssrc + " is stopped, not rescheduling task");
                }
                if (newWorkDueTs.equals(NO_REQUEST_DUE))
                {
                    logger.debug(() -> ssrc + " no more work to do, cancelling job handle");
                    if (currentTaskHandle != null)
                    {
                        currentTaskHandle.cancel(false);
                    }
                }
                else
                {
                    // TODO(brian): only re-schedule if the change is larger than X ms?
                    // The work is now due either sooner or later than we previously thought, so
                    // re-schedule the task
                    if (currentTaskHandle != null)
                    {
                        currentTaskHandle.cancel(false);
                    }
                    currentTaskHandle = scheduler.schedule(
                        this::doWork,
                        Duration.between(clock.instant(), newWorkDueTs).toMillis(),
                        TimeUnit.MILLISECONDS);
                }
            }
        }

        private void doWork()
        {
            logger.debug(() -> ssrc + " doing work at " + clock.instant());
            Instant now = clock.instant();
            SortedSet<Integer> missingSeqNums = getMissingSeqNums();
            if (missingSeqNums.size() >= maxMissingSeqNums)
            {
                logger.warn(ssrc + " sending NACK for " + missingSeqNums.size() + " missing packets");
            }
            RtcpFbNackPacketBuilder builder = new RtcpFbNackPacketBuilder(new RtcpHeaderBuilder(), ssrc, missingSeqNums);
            RtcpPacket nackPacket = builder.build();
            notifyNackSent(now, missingSeqNums);
            rtcpSender.accept(nackPacket);
        }

        private void notifyNackSent(Instant timestamp, Collection<Integer> nackedSeqNums)
        {
            synchronized (requests)
            {
                for (int nackedSeqNum : nackedSeqNums)
                {
                    // It's possible that in between sending the nack and calling this method the packet
                    // was received and is no longer in the requests map
                    PacketRetransmissionRequest request = requests.get(nackedSeqNum);
                    if (request != null)
                    {
                        request.requested(timestamp);
                        if (request.getNumTimesRequested() == MAX_REQUESTS)
                        {
                            logger.debug(() -> ssrc + " generated the last NACK for seq num " + request.getSeqNum() +
                                ", time since the first request = " +
                                Duration.between(request.getFirstRequestTimestamp(), timestamp));

                            requests.remove(nackedSeqNum);
                        }
                    }
                    else
                    {
                        logger.debug(() -> ssrc + " packet " + nackedSeqNum + " must have just been received, it was " +
                            "no longer in the requests map");
                    }
                }
                Instant nextDueTime = !requests.isEmpty() ? timestamp.plus(REQUEST_INTERVAL) : NO_REQUEST_DUE;
                logger.debug(() -> ssrc + " nack sent at " + timestamp + ", next one will be sent at " + nextDueTime);
                updateWorkDueTime(nextDueTime);
            }
        }

        private SortedSet<Integer> getMissingSeqNums()
        {
            synchronized (requests)
            {
                return new TreeSet<>(requests.keySet());
            }
        }
    }

    /**
     * Tracks a request for retransmission of a specific RTP packet.
     */
    private static class PacketRetransmissionRequest
    {
        private final int seqNum;
        private int numTimesRequested = 0;
        private Instant firstRequestTimestamp = Instant.MIN;

        PacketRetransmissionRequest(int seqNum)
        {
            this.seqNum = seqNum;
        }

        int getSeqNum()
        {
            return seqNum;
        }

        int getNumTimesRequested()
        {
            return numTimesRequested;
        }

        Instant getFirstRequestTimestamp()
        {
            return firstRequestTimestamp;
        }

        void requested(Instant timestamp)
        {
            if (firstRequestTimestamp.equals(Instant.MIN))
            {
                firstRequestTimestamp = timestamp;
            }
            numTimesRequested++;
        }
    }
}
