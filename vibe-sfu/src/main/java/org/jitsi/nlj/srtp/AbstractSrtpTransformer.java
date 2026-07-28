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

package org.jitsi.nlj.srtp;

import org.jitsi.nlj.PacketInfo;
import org.jitsi.srtp.BaseSrtpCryptoContext;
import org.jitsi.srtp.SrtpContextFactory;
import org.jitsi.srtp.SrtpErrorStatus;
import org.jitsi.utils.logging2.Logger;

import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the methods common to all 4 transformer implementation (encrypt/decrypt for SRTP/SRTCP)
 */
public abstract class AbstractSrtpTransformer<CryptoContextType extends BaseSrtpCryptoContext>
{
    protected final SrtpContextFactory contextFactory;
    protected final Logger logger;

    /**
     * All the known SSRC's corresponding SrtpCryptoContexts
     */
    private final Map<Long, CryptoContextType> contexts = new ConcurrentHashMap<>();

    protected AbstractSrtpTransformer(SrtpContextFactory contextFactory, Logger parentLogger)
    {
        this.contextFactory = contextFactory;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    public void close()
    {
        synchronized (contexts)
        {
            contextFactory.close();
        }
    }

    /**
     * Gets the context for a specific SSRC and index.
     */
    protected CryptoContextType getContext(long ssrc, long index) throws GeneralSecurityException
    {
        synchronized (contexts)
        {
            CryptoContextType existing = contexts.get(ssrc);
            if (existing != null)
            {
                return existing;
            }

            CryptoContextType derivedContext = deriveContext(ssrc, index);
            if (derivedContext == null)
            {
                logger.warn("Failed to derive context for " + ssrc + " " + index);
                return null;
            }
            contexts.put(ssrc, derivedContext);
            return derivedContext;
        }
    }

    /**
     * Derives a new context for a specific SSRC and index.
     */
    protected abstract CryptoContextType deriveContext(long ssrc, long index) throws GeneralSecurityException;

    /**
     * Gets the context to use for a specific packet.
     */
    protected abstract CryptoContextType getContext(PacketInfo packetInfo) throws GeneralSecurityException;

    /**
     * Does the actual transformation of a packet, with a specific context.
     */
    protected abstract SrtpErrorStatus transform(PacketInfo packetInfo, CryptoContextType context) throws GeneralSecurityException;

    /**
     * Transforms a packet, returns {@link SrtpErrorStatus#OK} on success or another {@link SrtpErrorStatus} on
     * failure.
     */
    public SrtpErrorStatus transform(PacketInfo packetInfo) throws GeneralSecurityException
    {
        CryptoContextType context = getContext(packetInfo);
        if (context == null)
        {
            return SrtpErrorStatus.FAIL;
        }

        return transform(packetInfo, context);
    }
}
