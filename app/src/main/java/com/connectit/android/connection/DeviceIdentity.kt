package com.connectit.android.connection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * 這台裝置的身分:一把固定的自簽 ECDSA P-256 憑證,配對連線一律走雙向 TLS(見 [ConnectionEngine]),
 * 兩邊各自出示自己的憑證,對應 Windows 端 DeviceIdentity.cs。憑證不是 CA 簽發的,不靠憑證鏈驗證身分,而是:
 * - 第一次配對時兩邊畫面都顯示同一組 6 位數配對碼([pairingCode],由雙方憑證指紋算出),使用者核對一致才接受;
 * - 勾選「信任此裝置」時記住對方的憑證指紋([fingerprint]),之後只認指紋、不認名稱。
 *
 * 私鑰放在 AndroidKeyStore 裡(不能匯出,解除安裝 App 才會消失),產生金鑰時系統會自動附上一張自簽憑證。
 * 指紋與配對碼的算法必須跟 Windows 端完全一致。
 */
class DeviceIdentity private constructor(
    private val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    /** 憑證 DER 的 SHA-256(小寫十六進位)。 */
    val fingerprint: String = fingerprintOf(certificate)

    /** 用這把身分建立的 TLS:出示自己的憑證,對方的憑證一律先接受(身分由配對碼與信任清單裡的指紋確認)。 */
    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(IdentityKeyManager()), arrayOf(AcceptAnyCertificate), SecureRandom())
        }.socketFactory
    }

    private inner class IdentityKeyManager : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)
        override fun getPrivateKey(alias: String?): PrivateKey = privateKey
    }

    private object AcceptAnyCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val ALIAS = "connectit_device_identity"
        private const val KEYSTORE = "AndroidKeyStore"

        /** 讀取 AndroidKeyStore 裡的身分;沒有就產生一把新的。 */
        fun loadOrCreate(): DeviceIdentity {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existingKey = runCatching { keyStore.getKey(ALIAS, null) as? PrivateKey }.getOrNull()
            val existingCert = runCatching { keyStore.getCertificate(ALIAS) as? X509Certificate }.getOrNull()
            if (existingKey != null && existingCert != null) {
                return DeviceIdentity(existingKey, existingCert)
            }

            val notBefore = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setCertificateSubject(X500Principal("CN=ConnectIt device"))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(notBefore)
                        .setCertificateNotAfter(notAfter)
                        .build()
                )
                generateKeyPair()
            }
            val key = keyStore.getKey(ALIAS, null) as PrivateKey
            val cert = keyStore.getCertificate(ALIAS) as X509Certificate
            return DeviceIdentity(key, cert)
        }

        fun fingerprintOf(certificate: Certificate): String = sha256Hex(certificate.encoded)

        /**
         * 兩台裝置的 6 位數配對碼:兩個指紋依字典序排好、用 ":" 串起來取 SHA-256,
         * 前 4 bytes 當成 big-endian 無號整數除以 1,000,000 的餘數,補零到 6 位。兩邊算出來一定一樣。
         */
        fun pairingCode(fingerprintA: String, fingerprintB: String): String {
            val (first, second) = if (fingerprintA <= fingerprintB) fingerprintA to fingerprintB else fingerprintB to fingerprintA
            val hash = MessageDigest.getInstance("SHA-256").digest("$first:$second".toByteArray(Charsets.UTF_8))
            val value = ((hash[0].toLong() and 0xFF) shl 24) or ((hash[1].toLong() and 0xFF) shl 16) or
                ((hash[2].toLong() and 0xFF) shl 8) or (hash[3].toLong() and 0xFF)
            return (value % 1_000_000).toString().padStart(6, '0')
        }

        /** 顯示用:「123 456」。 */
        fun formatPairingCode(code: String): String = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
