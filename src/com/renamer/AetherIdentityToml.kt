package com.renamer

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 1.51 — hand our already-registered WARP identity to aether through its
 *  TOML config file (the import path), instead of letting aether provision
 *  a fresh identity over the network.
 *
 *  WHY (1.50 device failure, 2026-11-19 ~23:00-23:02): every aether-masque
 *  probe died identically with "ثبت‌نام aether به پایان نرسید (تایم‌اوت 90
 *  ثانیه)". The aether rung never reached the MASQUE dial — it died inside
 *  aether's own identity provisioning. Root cause, verified in source:
 *  the FFI identity_open passed {"path": "<filesDir>/aether/aether.toml",
 *  "transport": "masque"} and nothing ever wrote that file, so
 *  api::open_identity (api.rs:319) took the "no identity at {path};
 *  provisioning a new one" branch → account::register → direct HTTP POST
 *  to api.cloudflareclient.com, which HANGS from the user's network
 *  without a working VPN (the 21:16 code -1 failure). Our own
 *  WarpIdentity registration works because OkHttp tries direct first,
 *  then the active tunnel's SOCKS; aether's internal api_call has no
 *  such fallback.
 *
 *  THE IMPORT PATH (verified in source — the 1.50 claim "FFI has no
 *  import path" was WRONG):
 *  - api::open_identity loads the identity from the TOML file when
 *    present ("[+] loaded an existing identity from {path}") with NO
 *    network; Asha's own Kotlin keeps identities in filesDir/aether.toml
 *    (AetherKeyManager.kt: FILE_WG_PRIMARY = "aether.toml").
 *  - The path is DERIVED: identity_path(base, Masque, None) =
 *    derive_sibling_path(base, "masque") (lib.rs:865) — it inserts
 *    "-masque" before the extension. So base ".../aether/aether.toml"
 *    is read as ".../aether/aether-masque.toml" (Asha's
 *    FILE_MASQUE_PRIMARY). This file is what we write.
 *  - With transport=masque, ProvisionRequest.masque_cert=true, so
 *    attach_masque_cert runs after the load — but it returns the
 *    identity UNCHANGED (no network) when has_masque_credentials():
 *    cert_pem + key_pem non-empty and cert_issued_at not expiring
 *    (365-day lifetime, 7-day renew-before; account.rs:827-847).
 *  - TOML shape is config::PersistedIdentity: device_id, access_token,
 *    cert_pem/key_pem (PEM text), cert_issued_at (unix SECONDS),
 *    ipv4, ipv6, wg_private_key/wg_peer_public_key (STANDARD base64
 *    of the 32 raw bytes — load FAILS unless exactly 32), client_id
 *    (STANDARD base64 of the 3 raw bytes; empty → zeros),
 *    organization, gateway_proxy, assigned_endpoint. A corrupt file
 *    is quarantined to "<name>.corrupt" and provisioning starts — so
 *    a bad write is loud, not silent.
 *
 *  THE CERT: aether's own generate_masque_keypair (account.rs:160)
 *  builds a self-signed X509 v3 cert: serial 0, EMPTY subject/issuer,
 *  notBefore=now, notAfter=now+365d, the P-256 public key, signed
 *  SHA256withECDSA. Our WarpIdentity enrolled only the SPKI via PATCH
 *  — it never had a certificate — so we build the SAME cert shape
 *  here in Kotlin from our already-enrolled P-256 key (hand-rolled
 *  DER; no Bouncy Castle in this tree). The server only ever sees
 *  the public key (the enrolled SPKI); the cert is its carrier.
 *  The cert is regenerated on every handoff (same key → same SPKI);
 *  nothing is stored — WarpIdentity stays the single source of truth.
 *
 *  Pure Kotlin + java.* — NO Android imports, so the writer is
 *  JVM-testable (see vm151 harness). */
object AetherIdentityToml {

    /** The file aether actually reads for transport=masque (derived
     *  from base "aether.toml" by derive_sibling_path). */
    const val MASQUE_FILE_NAME = "aether-masque.toml"
    /** 1.58 — the GOOL (WOM) inner identity file:
     *  the sibling the engine derives itself from
     *  AETHER_MASQUE_CONFIG (lib.rs:955-958
     *  gool_identity_path). Same TOML shape as the
     *  masque file, built from the "gool" identity. */
    const val GOOL_FILE_NAME = "aether-masque-gool.toml"

    /** aether's MASQUE cert lifetime (account.rs:827), in days. */
    const val MASQUE_CERT_LIFETIME_DAYS = 365L

    data class Input(
        val deviceId: String,
        val accessToken: String,
        val wgPrivateKeyB64: String,
        val wgPeerPublicKeyB64: String,
        /** Base64 of the 3 raw client_id bytes, as the API returned it. */
        val clientIdB64: String,
        /** Peer endpoint IP (port stripped) — aether's assigned_endpoint. */
        val wgPeerEndpointV4: String,
        val ipv4: String,
        val ipv6: String,
        /** PKCS#8 DER of the enrolled MASQUE P-256 key, base64. */
        val masquePrivateKeyB64: String,
        /** SPKI DER of the enrolled MASQUE P-256 key, base64. */
        val masquePublicSpkiB64: String
    )

    // ---- base64 (STANDARD alphabet, as aether's config.rs decodes) ----

    private fun b64decode(s: String): ByteArray? {
        val clean = s.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return null
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (_: Exception) {
            try { Base64.getMimeDecoder().decode(padded) } catch (_: Exception) { null }
        }
    }

    private fun b64encode(b: ByteArray): String =
        Base64.getEncoder().encodeToString(b)

    private fun pemEncode(label: String, der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.US_ASCII))
            .encodeToString(der)
        return "-----BEGIN $label-----\n$b64\n-----END $label-----\n"
    }

    // ---- minimal DER writer (for the self-signed cert) ----

    private fun derLen(out: ByteArrayOutputStream, len: Int) {
        when {
            len < 128 -> out.write(len)
            len < 256 -> { out.write(0x81); out.write(len) }
            len < 65536 -> { out.write(0x82); out.write(len shr 8); out.write(len and 0xff) }
            else -> { out.write(0x83); out.write(len shr 16); out.write(len shr 8 and 0xff); out.write(len and 0xff) }
        }
    }

    private fun derTlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        derLen(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun derSeq(vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (p in parts) body.write(p)
        return derTlv(0x30, body.toByteArray())
    }

    private fun derInt(v: BigInteger): ByteArray = derTlv(0x02, v.toByteArray())

    private fun utcTime(ms: Long): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return derTlv(0x17, f.format(Date(ms)).toByteArray(Charsets.US_ASCII))
    }

    /** OID 1.2.840.10045.4.3.2 (ecdsa-with-SHA256). */
    private val OID_ECDSA_SHA256 = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02
    )

    private fun sigAlg(): ByteArray =
        derSeq(derTlv(0x06, OID_ECDSA_SHA256), derTlv(0x05, ByteArray(0)))

    /** PKCS#8 DER -> "-----BEGIN PRIVATE KEY-----" PEM (aether's own
     *  private_key_to_pem_pkcs8 shape). Null when the key is blank or
     *  does not parse as an EC PKCS#8 key. */
    fun privateKeyToPem(pkcs8B64: String): String? {
        val der = b64decode(pkcs8B64) ?: return null
        return try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
            pemEncode("PRIVATE KEY", der)
        } catch (_: Exception) { null }
    }

    /** Self-signed X509 v3 cert, aether's exact template
     *  (generate_masque_keypair, account.rs:160): version v3, serial 0,
     *  EMPTY subject and issuer, no extensions, the given public key,
     *  SHA256withECDSA self-signature. Null when the key pair does not
     *  check out (private key unparseable, SPKI unparseable, or the
     *  public key does not match the private key). */
    fun buildMasqueCertPem(
        privateKeyPkcs8B64: String,
        publicSpkiB64: String,
        notBeforeMs: Long,
        notAfterMs: Long
    ): String? {
        return try {
            val privDer = b64decode(privateKeyPkcs8B64) ?: return null
            val spkiDer = b64decode(publicSpkiB64) ?: return null
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privDer))
            val pub = kf.generatePublic(X509EncodedKeySpec(spkiDer))
            // Pair check: the public key must belong to this private key.
            val probe = "aether-masque-cert-check".toByteArray(Charsets.UTF_8)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(priv)
            sig.update(probe)
            val sigBytes = sig.sign()
            val ver = Signature.getInstance("SHA256withECDSA")
            ver.initVerify(pub)
            ver.update(probe)
            if (!ver.verify(sigBytes)) return null
            // TBSCertificate: [0]{v3} serial sigAlg issuer validity subject spki
            val version = derTlv(0xA0, derInt(BigInteger.valueOf(2)))
            val serial = derInt(BigInteger.ZERO)
            val alg = sigAlg()
            val emptyName = derTlv(0x30, ByteArray(0))
            val validity = derSeq(utcTime(notBeforeMs), utcTime(notAfterMs))
            val tbs = derSeq(version, serial, alg, emptyName, validity, emptyName, spkiDer)
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(priv)
            signer.update(tbs)
            val tbsSig = signer.sign()
            val bitString = ByteArrayOutputStream().apply {
                write(0) // unused-bits byte
                write(tbsSig)
            }.toByteArray()
            val cert = derSeq(tbs, alg, derTlv(0x03, bitString))
            pemEncode("CERTIFICATE", cert)
        } catch (_: Exception) { null }
    }

    private fun tomlStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Build the full TOML text for aether's config::load. Null when
     *  the input cannot map (blank id/token, WG keys not exactly 32
     *  bytes, MASQUE key/cert not buildable). The PEM fields use the
     *  TOML literal multi-line string form (""\"...""\") — the same
     *  shape the vm150 harness proved aether parses (PEM never
     *  contains """). cert_issued_at is unix SECONDS of the cert's
     *  notBefore (the cert is generated now). */
    fun buildToml(input: Input, nowMs: Long = System.currentTimeMillis()): String? {
        val wgPriv = b64decode(input.wgPrivateKeyB64)?.takeIf { it.size == 32 } ?: return null
        val wgPeer = b64decode(input.wgPeerPublicKeyB64)?.takeIf { it.size == 32 } ?: return null
        val clientId = run {
            val raw = b64decode(input.clientIdB64)
            if (raw != null && raw.size == 3) b64encode(raw) else ""
        }
        if (input.deviceId.isBlank() || input.accessToken.isBlank()) return null
        if (input.masquePrivateKeyB64.isBlank() || input.masquePublicSpkiB64.isBlank()) return null
        val keyPem = privateKeyToPem(input.masquePrivateKeyB64) ?: return null
        val notAfter = nowMs + MASQUE_CERT_LIFETIME_DAYS * 86_400_000L
        val certPem = buildMasqueCertPem(
            input.masquePrivateKeyB64, input.masquePublicSpkiB64, nowMs, notAfter
        ) ?: return null
        val issuedAtSec = nowMs / 1000
        val sb = StringBuilder()
        sb.append("device_id = ").append(tomlStr(input.deviceId)).append('\n')
        sb.append("access_token = ").append(tomlStr(input.accessToken)).append('\n')
        sb.append("cert_pem = \"\"\"\n").append(certPem).append("\"\"\"\n")
        sb.append("key_pem = \"\"\"\n").append(keyPem).append("\"\"\"\n")
        sb.append("cert_issued_at = ").append(issuedAtSec).append('\n')
        sb.append("ipv4 = ").append(tomlStr(input.ipv4)).append('\n')
        sb.append("ipv6 = ").append(tomlStr(input.ipv6)).append('\n')
        sb.append("wg_private_key = ").append(tomlStr(b64encode(wgPriv))).append('\n')
        sb.append("wg_peer_public_key = ").append(tomlStr(b64encode(wgPeer))).append('\n')
        sb.append("client_id = ").append(tomlStr(clientId)).append('\n')
        sb.append("organization = \"\"\n")
        sb.append("gateway_proxy = \"\"\n")
        sb.append("assigned_endpoint = ").append(tomlStr(input.wgPeerEndpointV4)).append('\n')
        return sb.toString()
    }
}
