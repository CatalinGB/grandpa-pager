package com.grandpapager

import android.content.Context
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds a trust store that accepts the platform trust anchors, plus — in debug
 * builds only — the bundled local test CA.
 *
 * Paho's `mqttv3` client does not enable TLS endpoint identification by default,
 * so the returned factory wraps every socket and applies the `HTTPS` endpoint
 * identification algorithm. Without this, any certificate issued by a trusted CA
 * (including the test CA) would be accepted for any hostname — a MITM hole.
 */
object TlsUtil {

    fun socketFactory(context: Context): SSLSocketFactory {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)

        // The bundled CA is a LOCAL TEST credential: its private key lives on the
        // developer machine (gitignored). Trusting it in a release build would let
        // anyone holding that key impersonate the production broker, so only the
        // debug variant trusts it. Release trusts public CAs via the platform store.
        if (BuildConfig.DEBUG) {
            runCatching {
                context.resources.openRawResource(R.raw.ca).use { input ->
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(input)
                    ks.setCertificateEntry("pager-ca", cert)
                }
            }
        }

        // Platform trust anchors (managed brokers present a public certificate).
        runCatching {
            val def = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            def.init(null as KeyStore?)
            for (tm in def.trustManagers) {
                if (tm is X509TrustManager) {
                    tm.acceptedIssuers.forEach { c: X509Certificate ->
                        ks.setCertificateEntry("sys-" + c.subjectDN.name + "-" + c.serialNumber, c)
                    }
                }
            }
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, SecureRandom())
        return EndpointVerifyingSocketFactory(ctx.socketFactory)
    }
}

/** Delegates to the real factory but forces hostname verification on every socket. */
private class EndpointVerifyingSocketFactory(
    private val delegate: SSLSocketFactory
) : SSLSocketFactory() {

    private fun harden(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val params = socket.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = params
        }
        return socket
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        harden(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        harden(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        harden(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        harden(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        harden(delegate.createSocket(address, port, localAddress, localPort))
}
