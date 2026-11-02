package com.bridge.filetransfer

import com.bridge.security.BridgeTrustManager
import com.bridge.security.CertificateManager
import com.bridge.security.TrustStore
import com.bridge.security.TrustStoreAccess
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
     * The context for [deviceId] — the peer on the control connection that asked for this
     * transfer, never a device read out of the trust store. The store can hold more than
     * one Mac and holds them in no order, so "the paired device" is not a question it can
     * answer (BR-19).
     *
     * @throws NotPairedException when that peer is not pinned, so no listener is opened and
     * no connection is attempted — an unpaired peer gets no bytes rather than plaintext ones.
     */
    fun forPairedPeer(
        deviceId: String,
        trustStore: TrustStoreAccess = TrustStore.getInstance()
    ): SSLContext = forFingerprint(pinFor(deviceId, trustStore))

    /** Split out so the lookup can be tested without an Android Context. */
    fun pinFor(deviceId: String, trustStore: TrustStoreAccess): String =
        trustStore.pinnedFingerprint(deviceId) ?: throw NotPairedException()

    fun forFingerprint(peerFingerprint: String): SSLContext =
        CertificateManager.getInstance().createSSLContext(BridgeTrustManager(peerFingerprint))
}
