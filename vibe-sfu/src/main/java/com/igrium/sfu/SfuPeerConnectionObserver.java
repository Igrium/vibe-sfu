/*
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

package com.igrium.sfu;

/**
 * Receives lifecycle and data events from an {@link SfuPeerConnection}.
 *
 * <p>All methods have empty default implementations, so hosts implement only what
 * they need. Callbacks are invoked on internal library threads; implementations
 * must not block for long and must hand heavy work off to their own executors.
 *
 * <p><b>Where media lives.</b> Per-packet RTP is intentionally <em>not</em> on this
 * interface: decrypted inbound RTP is delivered through {@link MediaTrack#onRtpPacket}
 * (registered on the specific receive track), because that is the hot path and the
 * per-track handle is its natural home. Likewise a remote media stream becomes usable
 * through the {@link MediaTrack} returned by {@link SfuPeerConnection#addReceiveTrack}
 * rather than a separate {@code onTrack} event — in this signalling-agnostic library the
 * host learns of remote media from its own signalling (the remote's SDP) and registers
 * it explicitly, so the registering call already yields the track. This interface carries
 * the connection-wide events a host cannot get any other way: transport state, DTLS
 * outcome, data channels, bandwidth estimates, and the signal that the local media set
 * changed and must be re-negotiated.
 */
public interface SfuPeerConnectionObserver
{
    /**
     * The ICE connection state changed.
     */
    default void onIceConnectionStateChange(IceConnectionState state)
    {
    }

    /**
     * The connection is fully established: ICE is connected <em>and</em> the DTLS
     * handshake has completed. Data channels can be created/used from this point.
     */
    default void onConnected()
    {
    }

    /**
     * The transport has been lost (ICE failure).
     */
    default void onDisconnected()
    {
    }

    /**
     * The DTLS handshake failed. The connection cannot carry media or data after this;
     * the host should tear it down (and, if desired, retry with fresh transport). Mirrors
     * the error path upstream {@code DtlsTransport} logs before the endpoint expires.
     */
    default void onDtlsError(Throwable t)
    {
    }

    /**
     * The set of media this side sends has changed after the connection was first
     * negotiated (a send track was added or removed at runtime), so the host must generate
     * a fresh offer and exchange it with the remote peer over its own signalling — the
     * analogue of a browser {@code RTCPeerConnection}'s {@code negotiationneeded} event.
     *
     * <p>Only <em>local</em> (send-side) changes fire this: registering a
     * {@link SfuPeerConnection#addReceiveTrack receive track} is the host acting on media the
     * remote already offered (applied via {@link SfuPeerConnection#setRemoteDescription}),
     * not a reason to re-offer. Multiple synchronous changes are coalesced into a single
     * callback (as browsers queue one negotiation-needed check per task). Nothing fires
     * before the first {@link SfuPeerConnection#getLocalDescription()} — the initial
     * offer/answer already describes the starting media set.
     *
     * <p>This mirrors what upstream {@code org.jitsi.videobridge.Endpoint} does when its
     * forwarded-source or SSRC set changes: it pushes updated source/SSRC mappings to the
     * client to keep signalling in sync. Here the host owns the SDP, so the library surfaces
     * that same "your session changed, re-signal" moment as a callback instead.
     */
    default void onRenegotiationNeeded()
    {
    }

    /**
     * A local ICE candidate is available for the host to trickle to the remote peer.
     * This library gathers ICE candidates synchronously and returns
     * the <em>complete</em> set from {@link SfuPeerConnection#getLocalDescription()}, so
     * there is nothing to trickle from this side; the candidate list in the local
     * description is authoritative. Retained for symmetry with hosts that also implement
     * trickle from the remote via {@link SfuPeerConnection#addRemoteCandidate}.
     */
    default void onIceCandidate(org.jitsi.videobridge.transport.IceCandidate candidate)
    {
    }

    /**
     * The remote peer opened a data channel. The channel is already open and
     * usable when this fires.
     */
    default void onDataChannel(DataChannelTrack track)
    {
    }

    /**
     * A locally-created data channel finished opening (the remote side
     * acknowledged it).
     */
    default void onDataChannelOpen(DataChannelTrack track)
    {
    }

    /**
     * A string message arrived on a data channel.
     */
    default void onDataChannelStringMessage(DataChannelTrack track, String message)
    {
    }

    /**
     * A binary message arrived on a data channel.
     */
    default void onDataChannelBinaryMessage(DataChannelTrack track, byte[] message)
    {
    }

    /**
     * The current bandwidth estimate for this connection changed, in bits per second.
     * Forwarded straight from the media pipeline's bandwidth estimator (upstream
     * {@code TransceiverEventHandler.bandwidthEstimationChanged}); a forwarding host can use
     * it to decide how much media to relay onto this connection.
     */
    default void onBandwidthEstimateChanged(long bps)
    {
    }

    /**
     * The connection has been fully torn down (after {@link SfuPeerConnection#close()}).
     */
    default void onClosed()
    {
    }

    /**
     * An unrecoverable error occurred in the pipeline.
     */
    default void onError(Throwable t)
    {
    }
}
