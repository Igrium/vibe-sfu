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
package org.jitsi.nlj.rtp.bandwidthestimation2;

import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.Logger;

/** Configuration sent to factory create function. The parameters here are
 * optional to use for a network controller implementation.
 *
 * Base type for network controller,
 * based on WebRTC api/transport/network_control.h in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
public class NetworkControllerConfig
{
    public final Logger parentLogger;
    public final DiagnosticContext diagnosticContext;

    /** The initial constraints to start with, these can be changed at any later
     * time by calls to OnTargetRateConstraints. Note that the starting rate
     * has to be set initially to provide a starting state for the network
     * controller, even though the field is marked as nullable.
     */
    public final TargetRateConstraints constraints;

    /** Initial stream specific configuration, these are changed at any later time
     * by calls to OnStreamsConfig.
     */
    public final StreamsConfig streamBasedConfig;

    public NetworkControllerConfig(
        Logger parentLogger,
        DiagnosticContext diagnosticContext,
        TargetRateConstraints constraints,
        StreamsConfig streamBasedConfig)
    {
        this.parentLogger = parentLogger;
        this.diagnosticContext = diagnosticContext;
        this.constraints = constraints;
        this.streamBasedConfig = streamBasedConfig;
    }

    public NetworkControllerConfig(Logger parentLogger, DiagnosticContext diagnosticContext)
    {
        this(parentLogger, diagnosticContext, new TargetRateConstraints(), new StreamsConfig());
    }
}
