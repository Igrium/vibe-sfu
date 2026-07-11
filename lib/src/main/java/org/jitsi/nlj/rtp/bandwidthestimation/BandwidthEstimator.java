/*
 * Copyright @ 2019 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp.bandwidthestimation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.InstantKt;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * An abstract interface to a bandwidth estimation algorithm.
 *
 * The invoker of the algorithm will periodically call {@link #processPacketArrival}
 * and/or {@link #processPacketLoss} as it learns information about packets
 * that have traversed the network.
 *
 * All bandwidths/bitrates are in bits per second.
 */
public abstract class BandwidthEstimator
{
    protected final DiagnosticContext diagnosticContext;

    /**
     * The {@link TimeSeriesLogger} to be used by this instance to print time
     * series.
     */
    protected final TimeSeriesLogger timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(this.getClass());

    protected BandwidthEstimator(DiagnosticContext diagnosticContext)
    {
        this.diagnosticContext = diagnosticContext;
    }

    /** The name of the algorithm implemented by this {@link BandwidthEstimator}. */
    public abstract String getAlgorithmName();

    /** The initial bandwidth estimate. */
    public abstract Bandwidth getInitBw();
    public abstract void setInitBw(Bandwidth initBw);

    /** The minimum bandwidth the estimator will return. */
    public abstract Bandwidth getMinBw();
    public abstract void setMinBw(Bandwidth minBw);

    /** The maximum bandwidth the estimator will return. */
    public abstract Bandwidth getMaxBw();
    public abstract void setMaxBw(Bandwidth maxBw);

    /**
     * Inform the bandwidth estimator about a packet that has arrived at its
     * destination.
     *
     * This function will be called at most once for any value of {@code seq} (up to cycles);
     * however, it may be called after a call to {@link #processPacketLoss} for the
     * same {@code seq} value, if a packet is delayed.
     *
     * It is possible (e.g., if feedback was lost) that neither
     * {@link #processPacketArrival} nor {@link #processPacketLoss} is called for a given {@code seq}.
     *
     * The clocks reported by {@code now}, {@code sendTime}, and {@code recvTime} do not
     * necessarily have any relationship to each other, but must be consistent
     * within themselves across all calls to functions of this {@link BandwidthEstimator}.
     *
     * All arrival and loss reports based on a single feedback message should have the
     * same {@code now} value.  {@link #feedbackComplete} should be called once all feedback reports
     * based on a single feedback message have been processed.
     *
     * @param now The current time, when this function is called.
     * @param sendTime The time the packet was sent, if known, or null.
     * @param recvTime The time the packet was received, if known, or null.
     * @param seq A 16-bit sequence number of packets processed by this
     *  {@link BandwidthEstimator}.
     * @param size The size of the packet.
     * @param ecn The ECN markings with which the packet was received.
     * @param previouslyReportedLost Whether {@link #processPacketLoss} was previously
     *  called for this packet.
     */
    public void processPacketArrival(
        Instant now,
        Instant sendTime,
        Instant recvTime,
        int seq,
        DataSize size,
        byte ecn,
        boolean previouslyReportedLost
    )
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            DiagnosticContext.TimeSeriesPoint point = diagnosticContext.makeTimeSeriesPoint("bwe_packet_arrival", now);
            if (sendTime != null)
            {
                point.addField("sendTime", InstantKt.formatMilli(sendTime));
            }
            if (recvTime != null)
            {
                point.addField("recvTime", InstantKt.formatMilli(recvTime));
            }
            point.addField("seq", seq);
            point.addField("size", size.getBytes());
            if (ecn != (byte) 0)
            {
                point.addField("ecn", ecn);
            }
            point.addField("previouslyReportedLost", previouslyReportedLost);
            timeSeriesLogger.trace(point);
        }

        doProcessPacketArrival(now, sendTime, recvTime, seq, size, ecn, previouslyReportedLost);
    }

    public void processPacketArrival(Instant now, Instant sendTime, Instant recvTime, int seq, DataSize size)
    {
        processPacketArrival(now, sendTime, recvTime, seq, size, (byte) 0, false);
    }

    public void processPacketArrival(
        Instant now, Instant sendTime, Instant recvTime, int seq, DataSize size,
        boolean previouslyReportedLost)
    {
        processPacketArrival(now, sendTime, recvTime, seq, size, (byte) 0, previouslyReportedLost);
    }

    /**
     * A subclass's implementation of {@link #processPacketArrival}.
     *
     * See that function for parameter details.
     */
    protected abstract void doProcessPacketArrival(
        Instant now,
        Instant sendTime,
        Instant recvTime,
        int seq,
        DataSize size,
        byte ecn,
        boolean previouslyReportedLost
    );

    /**
     * Inform the bandwidth estimator that a packet was lost.
     *
     * All arrival and loss reports based on a single feedback message should have the
     * same {@code now} value.  {@link #feedbackComplete} should be called once all feedback reports
     * based on a single feedback message have been processed.
     *
     * @param now The current time, when this function is called.
     * @param sendTime The time the packet was sent, if known, or null.
     * @param seq A 16-bit sequence number of packets processed by this
     *  {@link BandwidthEstimator}.
     */
    public void processPacketLoss(Instant now, Instant sendTime, int seq)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            DiagnosticContext.TimeSeriesPoint point = diagnosticContext.makeTimeSeriesPoint("bwe_packet_loss", now);
            if (sendTime != null)
            {
                point.addField("sendTime", InstantKt.formatMilli(sendTime));
            }
            point.addField("seq", seq);
            timeSeriesLogger.trace(point);
        }

        doProcessPacketLoss(now, sendTime, seq);
    }

    /**
     * A subclass's implementation of {@link #processPacketLoss}.
     *
     * See that function for parameter details.
     */
    protected abstract void doProcessPacketLoss(Instant now, Instant sendTime, int seq);

    /**
     * Inform the bandwidth estimator that a block of feedback is complete.
     *
     * @param now The current time, when this function is called.  This should match
     *   the value passed to {@link #processPacketArrival} and {@link #processPacketLoss}.
     */
    public void feedbackComplete(Instant now)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            DiagnosticContext.TimeSeriesPoint point = diagnosticContext.makeTimeSeriesPoint("bwe_feedback_complete", now);
            timeSeriesLogger.trace(point);
        }

        doFeedbackComplete(now);
    }

    /**
     * A subclass's implementation of {@link #feedbackComplete}.
     *
     * See that function for parameter details.
     */
    protected abstract void doFeedbackComplete(Instant now);

    /**
     * Inform the bandwidth estimator about a new round-trip time value
     */
    public void onRttUpdate(Instant now, Duration newRtt)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            DiagnosticContext.TimeSeriesPoint point = diagnosticContext.makeTimeSeriesPoint("bwe_rtt", now);
            point.addField("rtt", DurationKt.formatMilli(newRtt));
            timeSeriesLogger.trace(point);
        }

        doRttUpdate(now, newRtt);
    }

    /**
     * A subclass's implementation of {@link #onRttUpdate}.
     *
     * See that function for parameter details.
     */
    protected abstract void doRttUpdate(Instant now, Duration newRtt);

    /** Get the estimator's current estimate of the available bandwidth.
     *
     * @param now The current time, when this function is called.
     */
    public abstract Bandwidth getCurrentBw(Instant now);

    /** Get the current statistics related to this estimator.
     *
     * @param now The current time, when this function is called.
     */
    public abstract StatisticsSnapshot getStats(Instant now);

    public StatisticsSnapshot getStats()
    {
        return getStats(Clock.systemUTC().instant());
    }

    /** Reset the estimator to its initial state. */
    public abstract void reset();

    private final LinkedList<TransportCcEngine.BandwidthListener> listeners = new LinkedList<>();
    private Bandwidth curBandwidth = Bandwidth.ofBps(-1);

    private Instant lastBweLogTime = InstantKt.NEVER;
    private static final Duration minBweLogInterval = Duration.ofMillis(500);

    /**
     * Notifies registered listeners that the estimate of the available
     * bandwidth has changed.
     */
    protected synchronized void reportBandwidthEstimate(Instant now, Bandwidth newValue)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            if (!newValue.equals(curBandwidth) ||
                lastBweLogTime.equals(InstantKt.NEVER) ||
                Duration.between(lastBweLogTime, now).compareTo(minBweLogInterval) >= 0)
            {
                DiagnosticContext.TimeSeriesPoint point = diagnosticContext.makeTimeSeriesPoint("bwe_estimate", now);
                point.addField("bw", newValue.getBps());
                timeSeriesLogger.trace(point);
                lastBweLogTime = now;
            }
        }

        if (newValue.equals(curBandwidth))
        {
            return;
        }
        for (TransportCcEngine.BandwidthListener listener : listeners)
        {
            listener.bandwidthEstimationChanged(newValue);
        }
        curBandwidth = newValue;
    }

    /**
     * Adds a listener to be notified about changes to the bandwidth estimation.
     * @param listener
     */
    public synchronized void addListener(TransportCcEngine.BandwidthListener listener)
    {
        listeners.add(listener);
    }

    /**
     * Removes a listener.
     * @param listener
     */
    public synchronized void removeListener(TransportCcEngine.BandwidthListener listener)
    {
        listeners.remove(listener);
    }

    /**
     * Holds a snapshot of stats specific to the bandwidth estimator.
     */
    public static class StatisticsSnapshot
    {
        // (Deviation: upstream uses `var x: T by stats` Kotlin map-delegated properties;
        // ported here as plain accessors reading/writing the same backing map.)
        private final Map<String, Object> stats = new LinkedHashMap<>();

        public StatisticsSnapshot(String algorithmName, Bandwidth currentEstimate)
        {
            addString("algorithmName", algorithmName);
            addBandwidth("currentEstimate", currentEstimate);
        }

        public String getAlgorithmName()
        {
            return (String) stats.get("algorithmName");
        }

        public void setAlgorithmName(String algorithmName)
        {
            stats.put("algorithmName", algorithmName);
        }

        public Bandwidth getCurrentEstimate()
        {
            return (Bandwidth) stats.get("currentEstimate");
        }

        public void setCurrentEstimate(Bandwidth currentEstimate)
        {
            stats.put("currentEstimate", currentEstimate);
        }

        public Object getValue(String name)
        {
            return stats.get(name);
        }

        /**
         * Gets the value of a stat with a given name, if this {@link StatisticsSnapshot} has it and it is a
         * {@link Number}. Otherwise returns {@code null}.
         */
        public Number getNumber(String name)
        {
            Object value = stats.get(name);
            return value instanceof Number ? (Number) value : null;
        }

        /**
         * Promotes integer values to {@link Long} and floating point values to {@link Double}. Returns a
         * {@link Long}, {@link Double}, or null.
         */
        private Number promote(Number n)
        {
            if (n instanceof Byte || n instanceof Short || n instanceof Integer || n instanceof Long)
            {
                return n.longValue();
            }
            else if (n instanceof Float || n instanceof Double)
            {
                return n.doubleValue();
            }
            return null;
        }

        /**
         * Adds a stat with a number value. Integral values are promoted to {@link Long}, while floating point
         * values are promoted to {@link Double}.
         */
        public void addNumber(String name, Number value)
        {
            Number promoted = promote(value);
            if (promoted != null)
            {
                stats.put(name, promoted);
            }
        }

        /**
         * Adds a stat with a string value.
         */
        public void addString(String name, String value)
        {
            stats.put(name, value);
        }

        /**
         * Adds a stat with a boolean value.
         */
        public void addBoolean(String name, boolean value)
        {
            stats.put(name, value);
        }

        /**
         * Adds a stat with a bandwidth value.
         */
        public void addBandwidth(String name, Bandwidth value)
        {
            stats.put(name, value);
        }

        /**
         * Returns a JSON representation of this {@link StatisticsSnapshot} object.
         */
        public ObjectNode toJson()
        {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            stats.forEach((name, value) -> {
                if (value instanceof Bandwidth)
                {
                    node.put(name, ((Bandwidth) value).getBps());
                }
                else if (value instanceof Long)
                {
                    node.put(name, (Long) value);
                }
                else if (value instanceof Integer)
                {
                    node.put(name, (Integer) value);
                }
                else if (value instanceof Double)
                {
                    node.put(name, (Double) value);
                }
                else if (value instanceof Boolean)
                {
                    node.put(name, (Boolean) value);
                }
                else
                {
                    node.put(name, value.toString());
                }
            });
            return node;
        }
    }
}
