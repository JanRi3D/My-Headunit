package me.ri3d.adb.adb

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec

/**
 * The phone's persistent ADB identity: a 2048-bit RSA key generated inside the Android Keystore.
 * The private key never leaves the Keystore; it only signs adbd's AUTH tokens.
 */
object KeystoreKey {
    private const val ALIAS = "my_headunit_adb_identity"

    fun load(): AdbKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) generate()
        val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        val name = "MyHeadunit@" + Build.MODEL.replace(Regex("""\s+"""), "_")
        return AdbKey.create(entry.certificate.publicKey as RSAPublicKey, name) { token -> AdbKey.signToken(entry.privateKey, token) }
    }

    private fun generate() {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            .setDigests(KeyProperties.DIGEST_NONE)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }
}
