package com.bridge.filetransfer

import com.bridge.security.BridgeTrustManager
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Fixed self-signed EC keystores for the payload TLS tests, generated once with keytool
 * and committed under `src/test/resources/tls`.
 *
 * Production certificates live in the Android Keystore, which does not exist in a JVM
 * unit test — so the real `CertificateManager` cannot be used here. These stand in for
 * it; the code under test sees the same `SSLContext` shape either way.
 */
object TestTls {

    private const val PASSWORD = "bridgetest"

    private fun load(peer: String): KeyStore {
        val stream = requireNotNull(TestTls::class.java.getResourceAsStream("/tls/$peer.p12")) {
            "missing test keystore /tls/$peer.p12"
        }
        return KeyStore.getInstance("PKCS12").apply { stream.use { load(it, PASSWORD.toCharArray()) } }
    }

    fun fingerprint(peer: String): String =
        BridgeTrustManager.fingerprint(load(peer).getCertificate("bridge") as X509Certificate)

    /** A context presenting [self]'s certificate and accepting only [pinnedPeer]'s. */
    fun context(self: String, pinnedPeer: String): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(load(self), PASSWORD.toCharArray())
        return SSLContext.getInstance("TLSv1.2").apply {
            init(kmf.keyManagers, arrayOf(BridgeTrustManager(fingerprint(pinnedPeer))), null)
        }
    }
}
