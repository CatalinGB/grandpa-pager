package com.grandpapager

import android.content.Context
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust store that accepts BOTH the bundled CA (own test broker) and the
 * platform anchors (managed brokers like Flespi).
 *
 * Wrapped so that Paho's `SSLSocketFactory.createSocket()` (no-arg) works —
 * the SSLContext-derived factory doesn't implement the unconnected-socket form,
 * which Paho always calls.
 */
object TlsUtil {

    fun socketFactory(context: Context): SSLSocketFactory {
        val base: SSLSocketFactory = runCatching { build(context) }
            .getOrElse { SSLSocketFactory.getDefault() as SSLSocketFactory }
        return object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

            override fun createSocket(): Socket =
                runCatching { base.createSocket() }
                    .getOrElse { (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() }

            override fun createSocket(host: String?, port: Int): Socket = base.createSocket(host, port)
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                base.createSocket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress?, port: Int): Socket = base.createSocket(host, port)
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                base.createSocket(address, port, localAddress, localPort)
            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                base.createSocket(s, host, port, autoClose)
        }
    }

    private fun build(context: Context): SSLSocketFactory {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)

        // 1. bundled private CA (res/raw/ca.crt)
        runCatching {
            context.resources.openRawResource(R.raw.ca).use { input ->
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(input)
                ks.setCertificateEntry("pager-ca", cert)
            }
        }

        // 2. platform trust anchors
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
