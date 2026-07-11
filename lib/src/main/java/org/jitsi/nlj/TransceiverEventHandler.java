/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj;

/**
 * Interface for handling events coming from a {@link Transceiver}
 * The intention is to extend if needed (e.g. merge with EndpointConnectionStats or a potential
 * RtpSenderEventHandler).
 *
 * (Deviation: upstream declares both {@code Transceiver} and {@code TransceiverEventHandler} in a single
 * {@code Transceiver.kt} file; Java requires one public top-level class per file, so this is split out.)
 */
public interface TransceiverEventHandler extends RtpReceiverEventHandler
{
}
