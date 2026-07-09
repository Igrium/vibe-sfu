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
package org.jitsi.nlj.dtls;

import java.security.KeyPair;
import java.util.Objects;

/**
 * Store various information about a generated Certificate, including:
 * The {@link KeyPair} used to build and sign it, as well as the fingerprint
 * hash function and the calculated fingerprint we use (which is transmitted
 * over the signaling channel so the certificate can be verified).  We also
 * store the time at which this certificate was created so we can refresh it
 * appropriately.
 */
public class CertificateInfo
{
    private final KeyPair keyPair;
    private final org.bouncycastle.tls.Certificate certificate;
    private final String localFingerprintHashFunction;
    private final String localFingerprint;
    private final long creationTimestampMs;

    public CertificateInfo(
        KeyPair keyPair,
        org.bouncycastle.tls.Certificate certificate,
        String localFingerprintHashFunction,
        String localFingerprint,
        long creationTimestampMs)
    {
        this.keyPair = keyPair;
        this.certificate = certificate;
        this.localFingerprintHashFunction = localFingerprintHashFunction;
        this.localFingerprint = localFingerprint;
        this.creationTimestampMs = creationTimestampMs;
    }

    public KeyPair getKeyPair()
    {
        return keyPair;
    }

    public org.bouncycastle.tls.Certificate getCertificate()
    {
        return certificate;
    }

    public String getLocalFingerprintHashFunction()
    {
        return localFingerprintHashFunction;
    }

    public String getLocalFingerprint()
    {
        return localFingerprint;
    }

    public long getCreationTimestampMs()
    {
        return creationTimestampMs;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof CertificateInfo))
        {
            return false;
        }
        CertificateInfo that = (CertificateInfo) o;
        return creationTimestampMs == that.creationTimestampMs &&
            Objects.equals(keyPair, that.keyPair) &&
            Objects.equals(certificate, that.certificate) &&
            Objects.equals(localFingerprintHashFunction, that.localFingerprintHashFunction) &&
            Objects.equals(localFingerprint, that.localFingerprint);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(keyPair, certificate, localFingerprintHashFunction, localFingerprint, creationTimestampMs);
    }
}
