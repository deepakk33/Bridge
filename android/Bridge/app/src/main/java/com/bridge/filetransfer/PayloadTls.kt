package com.bridge.filetransfer

import com.bridge.security.BridgeTrustManager
import com.bridge.security.CertificateManager
import com.bridge.security.TrustStore
import javax.net.ssl.SSLContext

/**
 * Mutual TLS for the out-of-band payload connection (BR-4).
 *
 * This is where the exposure actually is: the payload crosses a shared network and is
 * reachable by any host on it, where the BLE control channel needs physical proximity.
 * It is also the cheap side — the payload socket is a real `java.net.Socket`, so an
 * `SSLSocketFactory` layers straight over it.
 *
 * Verification is by pinned fingerprint only. No certificate authority, no hostname
 * check and no system trust store takes part in the decision.
 */
object PayloadTls {

    class NotPairedException :
        IllegalStateException("No pinned fingerprint — refusing to transfer")

    /**
     * The paired peer's context.
     *
     * @throws NotPairedException when nothing is pinned, so no listener is opened and no
     * connection is attempted — an unpaired peer gets no bytes rather than plaintext ones.
     */
    fun forPairedPeer(): SSLContext {
        val fingerprint = TrustStore.getInstance().pairedFingerprint() ?: throw NotPairedException()
        return forFingerprint(fingerprint)
    }

    fun forFingerprint(peerFingerprint: String): SSLContext =
        CertificateManager.getInstance().createSSLContext(BridgeTrustManager(peerFingerprint))
}
