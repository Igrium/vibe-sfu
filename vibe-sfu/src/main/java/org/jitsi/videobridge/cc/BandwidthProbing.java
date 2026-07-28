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

package org.jitsi.videobridge.cc;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jitsi.nlj.rtp.TransportCcEngine;
import org.jitsi.nlj.util.Bandwidth;
import org.jitsi.nlj.util.BitrateTracker;
import org.jitsi.nlj.util.DataSize;
import org.jitsi.utils.DurationKt;
import org.jitsi.utils.concurrent.PeriodicRunnable;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging.TimeSeriesLogger;
import org.jitsi.videobridge.cc.allocation.BitrateControllerStatusSnapshot;
import org.jitsi.videobridge.cc.config.BandwidthProbingConfig;

import java.util.Collection;
import java.util.function.Supplier;

public class BandwidthProbing extends PeriodicRunnable implements TransportCcEngine.BandwidthListener
{
    private static final TimeSeriesLogger timeSeriesLogger =
        TimeSeriesLogger.getTimeSeriesLogger(BandwidthProbing.class);

    private final ProbingDataSender probingDataSender;
    private final Supplier<BitrateControllerStatusSnapshot> statusSnapshotSupplier;

    /** Whether or not probing is currently enabled */
    public boolean enabled = false;

    private long lastTotalNeededBps = 0L;
    private long lastMaxPaddingBps = 0L;
    private long lastPaddingBps = 0L;

    private void zeroStats()
    {
        lastTotalNeededBps = 0;
        lastMaxPaddingBps = 0;
        lastPaddingBps = 0;
    }

    private final BitrateTracker probingBitrate =
        new BitrateTracker(DurationKt.getSecs(5), DurationKt.getMs(100));

    /**
     * The number of bytes left over from one run of probing to the next.  This
     * avoids accumulated rounding errors causing us to under-shoot the probing
     * bandwidth, and also handles the use when the number of bytes we want to
     * send is less than the size of an RTP header.
     */
    private double bytesLeftOver = 0.0;

    private long latestBwe = -1;

    public DiagnosticContext diagnosticsContext;

    public BandwidthProbing(
        ProbingDataSender probingDataSender,
        Supplier<BitrateControllerStatusSnapshot> statusSnapshotSupplier)
    {
        super(BandwidthProbingConfig.config.paddingPeriodMs);
        this.probingDataSender = probingDataSender;
        this.statusSnapshotSupplier = statusSnapshotSupplier;
    }

    @Override
    public void bandwidthEstimationChanged(Bandwidth newValue)
    {
        latestBwe = newValue.getBps();
    }

    @Override
    public void run()
    {
        super.run();
        if (!enabled)
        {
            zeroStats();
            return;
        }

        // We calculate how much to probe for based on the total target bps
        // (what we're able to reach), the total ideal bps (what we want to
        // be able to reach) and the total current bps (what we currently send).
        BitrateControllerStatusSnapshot bitrateControllerStatus = statusSnapshotSupplier.get();

        // How much padding do we need?
        long totalNeededBps = bitrateControllerStatus.getCurrentIdealBps() - bitrateControllerStatus.getCurrentTargetBps();
        if (totalNeededBps < 1 || !bitrateControllerStatus.isHasNonIdealLayer())
        {
            // Don't need to send any probing.
            bytesLeftOver = 0.0;
            zeroStats();
            return;
        }

        long latestBweCopy = latestBwe;
        if (bitrateControllerStatus.getCurrentIdealBps() <= latestBweCopy)
        {
            zeroStats();
            return;
        }

        // How much padding can we afford?
        long maxPaddingBps = latestBweCopy - bitrateControllerStatus.getCurrentTargetBps();
        long paddingBps = Math.min(totalNeededBps, maxPaddingBps);

        lastTotalNeededBps = totalNeededBps;
        lastMaxPaddingBps = maxPaddingBps;
        lastPaddingBps = paddingBps;

        DiagnosticContext.TimeSeriesPoint timeSeriesPoint = null;
        double newBytesNeeded = (BandwidthProbingConfig.config.paddingPeriodMs * paddingBps / 1000.0 / 8.0);
        double bytesNeeded = newBytesNeeded + bytesLeftOver;

        if (timeSeriesLogger.isTraceEnabled() && diagnosticsContext != null)
        {
            timeSeriesPoint = diagnosticsContext
                .makeTimeSeriesPoint("sent_padding")
                .addField("padding_bps", paddingBps)
                .addField("total_ideal_bps", bitrateControllerStatus.getCurrentIdealBps())
                .addField("total_target_bps", bitrateControllerStatus.getCurrentTargetBps())
                .addField("needed_bps", totalNeededBps)
                .addField("max_padding_bps", maxPaddingBps)
                .addField("bwe_bps", latestBweCopy)
                .addField("bytes_needed", bytesNeeded)
                .addField("prev_bytes_left_over", bytesLeftOver);
        }

        if (bytesNeeded >= 1)
        {
            int bytesSent = probingDataSender.sendProbing(bitrateControllerStatus.getActiveSsrcs(), (int) bytesNeeded);
            probingBitrate.update(DataSize.ofBytes(bytesSent));
            bytesLeftOver = Math.max(bytesNeeded - bytesSent, 0.0);
            if (timeSeriesPoint != null)
            {
                timeSeriesPoint.addField("bytes_sent", bytesSent).addField("new_bytes_left_over", bytesLeftOver);
            }
        }
        else
        {
            bytesLeftOver = Math.max(bytesNeeded, 0.0);
        }

        if (timeSeriesLogger.isTraceEnabled() && timeSeriesPoint != null)
        {
            timeSeriesLogger.trace(timeSeriesPoint);
        }
    }

    public ObjectNode getDebugState()
    {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("enabled", enabled);
        node.put("latest_bwe", latestBwe);
        node.put("last_total_needed_bps", lastTotalNeededBps);
        node.put("last_max_padding_bps", lastMaxPaddingBps);
        node.put("last_padding_bps", lastPaddingBps);
        node.put("probing_bps", probingBitrate.getRateBps());
        return node;
    }

    public interface ProbingDataSender
    {
        /**
         * Sends a specific number of bytes with a specific set of SSRCs.
         * @param mediaSsrcs the SSRCs
         * @param numBytes the number of probing bytes we want to send
         * @return the number of bytes of probing data actually sent
         */
        int sendProbing(Collection<Long> mediaSsrcs, int numBytes);
    }
}
