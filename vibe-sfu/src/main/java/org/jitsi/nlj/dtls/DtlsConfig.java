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
package org.jitsi.nlj.dtls;

import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.tls.CipherSuite;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Replaces the upstream {@code jitsi-metaconfig}-based {@code DtlsConfig}. Since this port does not use
 * {@code jitsi-metaconfig}/HOCON, this class simply hardcodes the upstream default values (see
 * {@code reference.conf}'s {@code jmt.dtls} section).
 */
public class DtlsConfig
{
    private static final List<String> DEFAULT_CIPHER_SUITES = Arrays.asList(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
    );

    private static final List<String> DEFAULT_ACCEPTED_FINGERPRINT_HASH_FUNCTIONS = Arrays.asList(
        "sha-512", "sha-384", "sha-256", "sha-1"
    );

    private static final String DEFAULT_LOCAL_FINGERPRINT_HASH_FUNCTION = "sha-256";

    // NOTE: must be declared after the DEFAULT_* fields above — the constructor reads them,
    // and static initializers run in declaration order.
    public static final DtlsConfig config = new DtlsConfig();

    private final Duration handshakeTimeout;
    private final List<Integer> cipherSuites;
    private final String localFingerprintHashFunction;
    private final List<String> acceptedFingerprintHashFunctions;

    private DtlsConfig()
    {
        this.handshakeTimeout = Duration.ofSeconds(30);

        List<Integer> ciphers = new ArrayList<>();
        for (String name : DEFAULT_CIPHER_SUITES)
        {
            ciphers.add(toBcCipherSuite(name));
        }
        if (ciphers.isEmpty())
        {
            throw new IllegalStateException("cipher-suites must not be empty");
        }
        this.cipherSuites = Collections.unmodifiableList(ciphers);

        this.localFingerprintHashFunction = validateHashFunction(DEFAULT_LOCAL_FINGERPRINT_HASH_FUNCTION);

        if (DEFAULT_ACCEPTED_FINGERPRINT_HASH_FUNCTIONS.isEmpty())
        {
            throw new IllegalStateException("accepted-fingerprint-hash-functions must not be empty");
        }
        List<String> accepted = new ArrayList<>();
        for (String func : DEFAULT_ACCEPTED_FINGERPRINT_HASH_FUNCTIONS)
        {
            accepted.add(validateHashFunction(func));
        }
        this.acceptedFingerprintHashFunctions = Collections.unmodifiableList(accepted);
    }

    public Duration getHandshakeTimeout()
    {
        return handshakeTimeout;
    }

    public List<Integer> getCipherSuites()
    {
        return cipherSuites;
    }

    public String getLocalFingerprintHashFunction()
    {
        return localFingerprintHashFunction;
    }

    public List<String> getAcceptedFingerprintHashFunctions()
    {
        return acceptedFingerprintHashFunctions;
    }

    private static String validateHashFunction(String func)
    {
        String ucFunc = func.toUpperCase();
        if (new DefaultDigestAlgorithmIdentifierFinder().find(ucFunc) == null)
        {
            throw new IllegalStateException("Unknown hash function " + func);
        }
        if (ucFunc.equals("MD5") || ucFunc.equals("MD2"))
        {
            throw new IllegalStateException("Forbidden hash function " + func);
        }
        return func.toLowerCase();
    }

    private static int toBcCipherSuite(String name)
    {
        try
        {
            return CipherSuite.class.getDeclaredField(name).getInt(null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Value is not a valid BouncyCastle cipher suite name: " + name, e);
        }
    }
}
