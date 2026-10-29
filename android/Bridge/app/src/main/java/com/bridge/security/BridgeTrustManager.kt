package com.bridge.security

import android.util.Log
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class BridgeTrustManager(
    private val expectedFingerprint: String? = null
) : X509TrustManager {

    companion object {
        private const val TAG = "BridgeTrustManager"

        fun fingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(cert.encoded)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        verifyChain(chain)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        verifyChain(chain)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

    private fun verifyChain(chain: Array<out X509Certificate>) {
        if (chain.isEmpty()) {
            throw CertificateException("No certificates in chain")
        }

        val peerCert = chain[0]
        val peerFingerprint = fingerprint(peerCert)

        if (expectedFingerprint == null) {
            // Fail closed. A caller that forgets to pass a pin gets a refused handshake,
            // not a peer accepted on the strength of having presented any certificate.
            Log.w(TAG, "No pinned fingerprint to check against — refusing $peerFingerprint")
            throw CertificateException("No pinned fingerprint for this peer")
        }

        if (peerFingerprint != expectedFingerprint) {
            Log.w(TAG, "Fingerprint mismatch: expected=$expectedFingerprint actual=$peerFingerprint")
            throw CertificateException("Certificate fingerprint mismatch")
        }
        Log.i(TAG, "Peer fingerprint verified")
    }
}
