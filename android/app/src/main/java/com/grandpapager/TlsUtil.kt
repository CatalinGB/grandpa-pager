package com.grandpapager

import android.content.Context
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds a trust store that accepts BOTH the bundled CA (used by our own
 * test broker) and the platform trust anchors (used by managed brokers such
 * as EMQX, which present a public certificate).
 */
object TlsUtil {

    fun socketFactory(context: Context): SSLSocketFactory {
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
