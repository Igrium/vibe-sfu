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

import java.util.Locale;

/**
 * The direction of a {@link MediaTransceiver}, from <em>this</em> side's point of view.
 * Mirrors the browser {@code RTCRtpTransceiverDirection} enum and the SDP direction
 * attributes ({@code a=sendrecv} and friends).
 */
public enum MediaDirection
{
    /** We both send and receive on this transceiver. */
    SENDRECV("sendrecv"),
    /** We only send; the remote peer only receives. */
    SENDONLY("sendonly"),
    /** We only receive; the remote peer only sends. */
    RECVONLY("recvonly"),
    /** No media flows in either direction. */
    INACTIVE("inactive");

    private final String sdpToken;

    MediaDirection(String sdpToken)
    {
        this.sdpToken = sdpToken;
    }

    /** The SDP attribute name for this direction, e.g. {@code "sendrecv"} (without the {@code a=}). */
    public String getSdpToken()
    {
        return sdpToken;
    }

    /** Whether this side sends media on a transceiver with this direction. */
    public boolean isSending()
    {
        return this == SENDRECV || this == SENDONLY;
    }

    /** Whether this side receives media on a transceiver with this direction. */
    public boolean isReceiving()
    {
        return this == SENDRECV || this == RECVONLY;
    }

    /**
     * This direction as seen from the other side of the connection: {@link #SENDONLY} and
     * {@link #RECVONLY} swap, the symmetric directions are unchanged. Useful when turning a
     * direction we offered into the direction we expect the peer to answer with.
     */
    public MediaDirection reversed()
    {
        switch (this)
        {
            case SENDONLY:
                return RECVONLY;
            case RECVONLY:
                return SENDONLY;
            default:
                return this;
        }
    }

    /**
     * Parses an SDP direction token ({@code "sendrecv"}, {@code "sendonly"}, {@code "recvonly"},
     * {@code "inactive"}), case-insensitively.
     *
     * @param token the token, with or without a leading {@code "a="}.
     * @param fallback returned when {@code token} is null or unrecognised.
     */
    public static MediaDirection fromSdpToken(String token, MediaDirection fallback)
    {
        if (token == null)
        {
            return fallback;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("a="))
        {
            normalized = normalized.substring("a=".length());
        }
        for (MediaDirection direction : values())
        {
            if (direction.sdpToken.equals(normalized))
            {
                return direction;
            }
        }
        return fallback;
    }
}
