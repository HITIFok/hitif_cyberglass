package com.hitif.videodownloader.download

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Custom OkHttp DNS resolver with fallback to public DNS-over-HTTPS servers.
 *
 * Resolution order:
 *   1. System DNS (default, fastest for cached results)
 *   2. Google DNS-over-HTTPS (dns.google)
 *   3. Cloudflare DNS-over-HTTPS (cloudflare-dns.com)
 *
 * This fixes "Unable to resolve host" errors for short-lived proxy hostnames
 * (e.g. prx-*.vmwesa.online) that may not be resolvable by the device's
 * local DNS resolver but are available via public DNS.
 */
class FallbackDns : Dns {

    companion object {
        private const val TAG = "FallbackDns"
    }

    private val systemDns = Dns.SYSTEM

    // Separate OkHttp client for DoH queries — must NOT use FallbackDns to avoid infinite recursion
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. System DNS (uses cached results from the device's DNS resolver)
        try {
            val addresses = systemDns.lookup(hostname)
            if (addresses.isNotEmpty()) {
                Log.d(TAG, "DNS OK (system): $hostname -> ${addresses.first()}")
                return addresses
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "DNS system failed: $hostname — ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "DNS system error: $hostname — ${e.message}")
        }

        // 2. Google DNS-over-HTTPS (https://dns.google/resolve?name=...&type=A)
        try {
            val addresses = resolveViaGoogleDoH(hostname)
            if (addresses.isNotEmpty()) {
                Log.d(TAG, "DNS OK (Google DoH): $hostname -> ${addresses.first()}")
                return addresses
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google DoH failed for $hostname: ${e.message}")
        }

        // 3. Cloudflare DNS-over-HTTPS (https://cloudflare-dns.com/dns-query?name=...&type=A)
        try {
            val addresses = resolveViaCloudflareDoH(hostname)
            if (addresses.isNotEmpty()) {
                Log.d(TAG, "DNS OK (Cloudflare DoH): $hostname -> ${addresses.first()}")
                return addresses
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloudflare DoH failed for $hostname: ${e.message}")
        }

        // 4. One last retry with system DNS (DNS may have propagated during the DoH queries)
        try {
            Thread.sleep(500)
            val addresses = systemDns.lookup(hostname)
            if (addresses.isNotEmpty()) {
                Log.d(TAG, "DNS OK (system retry): $hostname -> ${addresses.first()}")
                return addresses
            }
        } catch (_: Exception) {}

        // All DNS methods failed
        Log.e(TAG, "DNS FAIL: $hostname — tried system, Google DoH, Cloudflare DoH")
        throw UnknownHostException(
            "Unable to resolve host \"$hostname\": No address found. " +
            "Verifiez votre connexion internet et reessayez."
        )
    }

    // -----------------------------------------------------------------------
    // Google DNS-over-HTTPS
    // Response: {"Answer":[{"name":"...","type":1,"TTL":300,"data":"1.2.3.4"}]}
    // -----------------------------------------------------------------------

    private fun resolveViaGoogleDoH(hostname: String): List<InetAddress> {
        val url = "https://dns.google/resolve?name=${hostname}&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()

        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()

            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()

            val result = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                val type = answer.optInt("type", 0)
                val data = answer.optString("data", "")
                // Only A records (type=1) and valid IPv4 addresses
                if (type == 1 && data.isNotBlank() && data.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                    try {
                        result.add(InetAddress.getByName(data))
                    } catch (_: Exception) {}
                }
            }
            return result
        }
    }

    // -----------------------------------------------------------------------
    // Cloudflare DNS-over-HTTPS
    // Response format: same as Google DoH
    // -----------------------------------------------------------------------

    private fun resolveViaCloudflareDoH(hostname: String): List<InetAddress> {
        val url = "https://cloudflare-dns.com/dns-query?name=${hostname}&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()

        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()

            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()

            val result = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                val type = answer.optInt("type", 0)
                val data = answer.optString("data", "")
                if (type == 1 && data.isNotBlank() && data.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                    try {
                        result.add(InetAddress.getByName(data))
                    } catch (_: Exception) {}
                }
            }
            return result
        }
    }
}
