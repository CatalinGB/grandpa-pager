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
 * Builds the TLS socket factory for the MQTT connection.
 *
 * Trust store = the platform trust anchors, plus — **in debug builds only** — the
 * bundled local test CA (`res/raw/ca.crt`). Its private key lives on the developer
 * machine, so trusting it in a release build would let anyone holding that key
 * impersonate the production broker. Release therefore trusts public CAs only.
 *
 * The factory is wrapped to:
 *  - implement Paho's no-arg `createSocket()` (its SSLContext-derived factory does
 *    not, which breaks the connection), and
 *  - force the `HTTPS` endpoint-identification algorithm on every socket, so the
 *    server certificate must match the host we asked for.
 *
 * Caveat: Paho connects via the no-arg socket, so endpoint identification may not
 * always have a hostname to check; the primary protection remains trusting only
 * public CAs (never the test CA in release) on top of TLS.
 */
object TlsUtil {

    fun socketFactory(context: Context): SSLSocketFactory {
        val base: SSLSocketFactory = runCatching { build(context) }
            .getOrElse { SSLSocketFactory.getDefault() as SSLSocketFactory }
        return object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

            override fun createSocket(): Socket =
                harden(runCatching { base.createSocket() }
                    .getOrElse { (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() })

            override fun createSocket(host: String?, port: Int): Socket =
                harden(base.createSocket(host, port))

            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                harden(base.createSocket(host, port, localHost, localPort))

            override fun createSocket(host: InetAddress?, port: Int): Socket =
                harden(base.createSocket(host, port))

            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                harden(base.createSocket(address, port, localAddress, localPort))

            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                harden(base.createSocket(s, host, port, autoClose))
        }
    }

    private fun harden(socket: Socket): Socket {
        if (socket is SSLSocket) {
            runCatching {
                val params = socket.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                socket.sslParameters = params
            }
        }
        return socket
    }

    private fun build(context: Context): SSLSocketFactory {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)

        // 1. bundled local test CA — DEBUG ONLY (its key is not secret, but it
        //    must never be trusted in a released build).
        if (BuildConfig.DEBUG) {
            runCatching {
                context.resources.openRawResource(R.raw.ca).use { input ->
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(input)
                    ks.setCertificateEntry("pager-ca", cert)
                }
            }
        }

        // 2. platform trust anchors (managed brokers present a public certificate)
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
        return ctx.socketFactory
    }
}
