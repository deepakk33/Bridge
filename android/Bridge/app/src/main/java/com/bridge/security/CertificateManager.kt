package com.bridge.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import android.security.keystore.KeyInfo
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

class CertificateManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CertificateManager"
        private const val KEYSTORE_ALIAS = "bridge_device_cert"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        @Volatile
        private var instance: CertificateManager? = null

        fun init(context: Context): CertificateManager {
            return instance ?: synchronized(this) {
                instance ?: CertificateManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun getInstance(): CertificateManager {
            return instance ?: throw IllegalStateException("CertificateManager not initialized")
        }
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /**
     * Ensures a key that can actually sign a TLS handshake. Returns true when an unusable key had
     * to be replaced, which changes this device's fingerprint and invalidates existing pairings.
     */
    fun ensureCertificate(): Boolean {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            generateCertificate()
            return false
        }
        if (!KeyDigestPolicy.needsRegeneration(authorisedDigests())) {
            Log.i(TAG, "Certificate already exists")
            return false
        }
        // Pre-BR-8 keys permit SHA-256 only, so conscrypt's raw-digest signing is refused and no
        // TLS handshake can complete. Authorisations are fixed at generation: replace the key.
        Log.w(TAG, "Replacing a key that cannot sign for TLS; this device must be paired again")
        keyStore.deleteEntry(KEYSTORE_ALIAS)
        generateCertificate()
        return true
    }

    /** Empty when the key reports no digest restriction, or when they cannot be read. */
    private fun authorisedDigests(): List<String> = try {
        val key = keyStore.getKey(KEYSTORE_ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java)
        info.digests.toList()
    } catch (e: Exception) {
        Log.w(TAG, "Could not read key digests: ${e.message}")
        emptyList()
    }

    fun getFingerprint(): String {
        val cert = getCertificate()
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(cert.encoded)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getCertificate(): X509Certificate {
        val entry = keyStore.getCertificate(KEYSTORE_ALIAS)
            ?: throw IllegalStateException("No certificate found")
        return entry as X509Certificate
    }

    fun createSSLContext(trustManager: BridgeTrustManager): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(kmf.keyManagers, arrayOf(trustManager), null)
        return sslContext
    }

    private fun generateCertificate() {
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setCertificateSubject(X500Principal("CN=Bridge Device"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(start.time)
            .setCertificateNotAfter(end.time)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        kpg.initialize(spec)
        kpg.generateKeyPair()

        Log.i(TAG, "Generated new self-signed certificate")
    }
}
