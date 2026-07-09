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

import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDefaultDigestProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.tls.SecurityParameters;
import org.bouncycastle.tls.TlsContext;
import org.bouncycastle.tls.TlsUtils;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsSecret;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Various helper utilities for DTLS
 *
 * https://tools.ietf.org/html/draft-ietf-rtcweb-security-arch-18
 */
public class DtlsUtils
{
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final BcTlsCrypto BC_TLS_CRYPTO = new BcTlsCrypto(SECURE_RANDOM);

    private static final char[] HEX_CHARS = "0123456789ABCDEF".toCharArray();

    static
    {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static CertificateInfo generateCertificateInfo() throws GeneralSecurityException, OperatorCreationException
    {
        X500Name cn = generateCN("TODO-APP-NAME", "TODO-APP-VERSION");
        KeyPair keyPair = generateEcKeyPair();
        Certificate x509certificate = generateCertificate(cn, keyPair);
        String localFingerprintHashFunction = DtlsConfig.config.getLocalFingerprintHashFunction();
        String localFingerprint = getFingerprint(x509certificate, localFingerprintHashFunction);

        org.bouncycastle.tls.Certificate certificate = new org.bouncycastle.tls.Certificate(
            new BcTlsCertificate[] { new BcTlsCertificate(BC_TLS_CRYPTO, x509certificate) }
        );
        return new CertificateInfo(
            keyPair,
            certificate,
            localFingerprintHashFunction,
            localFingerprint,
            System.currentTimeMillis()
        );
    }

    /**
     * A helper which finds an SRTP protection profile present in both
     * {@code ours} and {@code theirs}.  Throws {@link DtlsException} if no common profile is found.
     */
    public static int chooseSrtpProtectionProfile(Iterable<Integer> ours, Iterable<Integer> theirs)
    {
        for (Integer candidate : ours)
        {
            for (Integer their : theirs)
            {
                if (their.equals(candidate))
                {
                    return candidate;
                }
            }
        }
        throw new DtlsException(
            "No common SRTP protection profile found.  Ours: " + joinToString(ours) +
                " Theirs: " + joinToString(theirs)
        );
    }

    private static String joinToString(Iterable<Integer> values)
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Integer value : values)
        {
            if (!first)
            {
                sb.append(", ");
            }
            sb.append(value);
            first = false;
        }
        return sb.toString();
    }

    /**
     * Generate an x509 certificate valid from 1 day ago until 7 days from now.
     *
     * TODO: make the algorithm dynamic (passed in) to support older dtls versions/clients
     */
    private static Certificate generateCertificate(X500Name subject, KeyPair keyPair) throws OperatorCreationException
    {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now - Duration.ofDays(1).toMillis());
        Date expiryDate = new Date(now + Duration.ofDays(7).toMillis());
        BigInteger serialNumber = BigInteger.valueOf(now);

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            subject,
            serialNumber,
            startDate,
            expiryDate,
            subject,
            keyPair.getPublic()
        );
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());

        return certBuilder.build(signer).toASN1Structure();
    }

    /**
     * Generate an eliptic-curve keypair using the secp256r1 named curve:
     * "All Implementations MUST implement DTLS 1.2 with the
     * TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 cipher suite and the P-256
     * curve"
     *
     * --https://tools.ietf.org/html/draft-ietf-rtcweb-security-arch-18#section-6.5
     *
     * NOTE(brian): I used 'secp256r1' specifically because it's what I saw in wireshark traces from chrome
     */
    private static KeyPair generateEcKeyPair() throws NoSuchAlgorithmException, NoSuchProviderException, GeneralSecurityException
    {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", "BC");
        ECNamedCurveParameterSpec ecCurveSpec = ECNamedCurveTable.getParameterSpec("secp256r1");

        keyGen.initialize(ecCurveSpec);

        return keyGen.generateKeyPair();
    }

    /**
     * Generate an {@link X500Name} using the given {@code appName} and {@code appVersion}
     */
    private static X500Name generateCN(String appName, String appVersion)
    {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        String rdn = appName + " " + appVersion;
        builder.addRDN(BCStyle.CN, rdn);
        return builder.build();
    }

    /**
     * Verifies and validates a specific certificate against the fingerprints
     * presented by the remote endpoint via the signaling path.
     *
     * @param certificateInfo the certificate to be verified and validated against
     * the fingerprints presented by the remote endpoint via the signaling path
     * @throws DtlsException if {@code certificateInfo} fails validation
     */
    public static void verifyAndValidateCertificate(
        org.bouncycastle.tls.Certificate certificateInfo,
        Map<String, List<String>> remoteFingerprints)
    {
        if (certificateInfo.getCertificateList().length == 0)
        {
            throw new DtlsException("No remote fingerprints.");
        }
        for (TlsCertificate currCertificate : certificateInfo.getCertificateList())
        {
            Certificate x509Cert;
            try
            {
                x509Cert = Certificate.getInstance(currCertificate.getEncoded());
            }
            catch (IOException e)
            {
                throw new DtlsException("Failed to decode remote certificate: " + e);
            }
            verifyAndValidateCertificate(x509Cert, remoteFingerprints);
        }
    }

    /**
     * Verifies and validates a specific certificate against the fingerprints
     * presented by the remote endpoint via the signaling path.
     *
     * @param certificate the certificate to be verified and validated against
     * the fingerprints presented by the remote endpoint via the signaling path.
     * @throws DtlsException if the specified {@code certificate} failed to verify
     * and validate against the fingerprints presented by the remote endpoint
     * via the signaling path.
     */
    private static void verifyAndValidateCertificate(Certificate certificate, Map<String, List<String>> remoteFingerprints)
    {
        /* RFC 8122:
         *    An endpoint MUST select the set of fingerprints that use its most
         *    preferred hash function (out of those offered by the peer) and verify
         *    that each certificate used matches one fingerprint out of that set.
         */
        for (String hashFunction : DtlsConfig.config.getAcceptedFingerprintHashFunctions())
        {
            List<String> fingerprints = remoteFingerprints.get(hashFunction);
            if (fingerprints == null)
            {
                continue;
            }

            String certificateFingerprint = getFingerprint(certificate, hashFunction);

            boolean matches = false;
            for (String fp : fingerprints)
            {
                if (fp.equals(certificateFingerprint))
                {
                    matches = true;
                    break;
                }
            }
            if (!matches)
            {
                throw new DtlsException(
                    "None of the fingerprints " + String.join(", ", fingerprints) + " match the " + hashFunction +
                        "-hashed certificate " + certificateFingerprint
                );
            }
            return;
        }
        /* If we got here none of our accepted fingerprint functions were listed. */
        throw new DtlsException(
            "No fingerprint declared over the signaling path with any of the accepted hash functions: " +
                String.join(", ", DtlsConfig.config.getAcceptedFingerprintHashFunctions())
        );
    }

    /**
     * Computes the fingerprint of a {@link Certificate} using {@code hashFunction} and returns it
     * as a {@link String}
     */
    private static String getFingerprint(Certificate certificate, String hashFunction)
    {
        try
        {
            org.bouncycastle.asn1.x509.AlgorithmIdentifier digAlgId =
                new DefaultDigestAlgorithmIdentifierFinder().find(hashFunction.toUpperCase());
            org.bouncycastle.crypto.Digest digest = BcDefaultDigestProvider.INSTANCE.get(digAlgId);
            byte[] input = certificate.getEncoded(ASN1Encoding.DER);
            byte[] output = new byte[digest.getDigestSize()];

            digest.update(input, 0, input.length);
            digest.doFinal(output, 0);

            return toFingerprint(output);
        }
        catch (IOException | OperatorCreationException e)
        {
            throw new DtlsException("Failed to compute certificate fingerprint: " + e);
        }
    }

    /**
     * Helper function to convert a {@code byte[]} to a colon-delimited hex string
     */
    private static String toFingerprint(byte[] bytes)
    {
        StringBuffer buf = new StringBuffer();
        for (int i = 0; i < bytes.length; i++)
        {
            int octet = bytes[i];
            int firstIndex = (octet & 0xF0) >>> 4;
            int secondIndex = octet & 0x0F;
            buf.append(HEX_CHARS[firstIndex]);
            buf.append(HEX_CHARS[secondIndex]);
            if (i < bytes.length - 1)
            {
                buf.append(":");
            }
        }
        return buf.toString();
    }

    /*
     * Copied from TlsContext#exportKeyingMaterial and modified to work with
     * an externally provided masterSecret value.
     */
    public static byte[] exportKeyingMaterial(
        TlsContext context,
        String asciiLabel,
        byte[] context_value,
        int length,
        TlsSecret masterSecret)
    {
        if (context_value != null && !TlsUtils.isValidUint16(context_value.length))
        {
            throw new IllegalArgumentException("'context_value' must have a length less than 2^16 (or be null)");
        }
        org.bouncycastle.tls.SecurityParameters sp = context.getSecurityParameters();
        byte[] cr = sp.getClientRandom();
        byte[] sr = sp.getServerRandom();

        int seedLength = cr.length + sr.length;
        if (context_value != null)
        {
            seedLength += (2 + context_value.length);
        }

        byte[] seed = new byte[seedLength];
        int seedPos = 0;

        System.arraycopy(cr, 0, seed, seedPos, cr.length);
        seedPos += cr.length;
        System.arraycopy(sr, 0, seed, seedPos, sr.length);
        seedPos += sr.length;

        if (context_value != null)
        {
            TlsUtils.writeUint16(context_value.length, seed, seedPos);
            seedPos += 2;
            System.arraycopy(context_value, 0, seed, seedPos, context_value.length);
            seedPos += context_value.length;
        }

        if (seedPos != seedLength)
        {
            throw new IllegalStateException("error in calculation of seed for export");
        }

        return TlsUtils.PRF(sp, masterSecret, asciiLabel, seed, length).extract();
    }

    /** Avoid adding to the trace in the log file */
    public static void notifyAlertRaised(
        org.jitsi.utils.logging2.Logger logger,
        short alertLevel,
        short alertDescription,
        String message,
        Throwable cause)
    {
        if (alertDescription == org.bouncycastle.tls.AlertDescription.close_notify)
        {
            logger.debug(() -> "close_notify raised, connection closing");
        }
        else
        {
            String stack;
            StringBuffer sb = new StringBuffer();
            Exception e = new Exception();
            for (StackTraceElement el : e.getStackTrace())
            {
                sb.append(el.toString()).append('\n');
            }
            stack = sb.toString();
            logger.info(() -> "Alert raised: level=" + alertLevel + ", description=" + alertDescription +
                ", message=" + message + " cause=" + cause + " " + stack);
        }
    }

    /** Avoid adding to the trace in the log file */
    public static void notifyAlertReceived(org.jitsi.utils.logging2.Logger logger, short alertLevel, short alertDescription)
    {
        if (alertDescription == org.bouncycastle.tls.AlertDescription.close_notify)
        {
            logger.info(() -> "close_notify received, connection closing");
        }
        else
        {
            logger.error(() -> "Alert received: level=" + alertLevel + ", description=" + alertDescription +
                " (" + org.bouncycastle.tls.AlertDescription.getName(alertDescription) + ")");
        }
    }

    public static class DtlsException extends RuntimeException
    {
        public DtlsException(String msg)
        {
            super(msg);
        }
    }
}
