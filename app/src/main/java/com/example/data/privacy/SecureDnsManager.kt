package com.example.data.privacy

import com.example.data.diagnostics.DiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

enum class DnsProvider(val displayName: String, val dohEndpoint: String) {
    CLOUDFLARE("Cloudflare 1.1.1.1 (تشفير DoH سريع)", "https://1.1.1.1/dns-query"),
    GOOGLE("Google 8.8.8.8 (استجابة فائقة)", "https://dns.google/resolve"),
    ADGUARD("AdGuard Shield (حماية ومنع إعلانات)", "https://dns.adguard-dns.com/resolve")
}

object SecureDnsManager {
    private val _isShieldActive = MutableStateFlow(true)
    val isShieldActive: StateFlow<Boolean> = _isShieldActive.asStateFlow()

    private val _selectedProvider = MutableStateFlow(DnsProvider.CLOUDFLARE)
    val selectedProvider: StateFlow<DnsProvider> = _selectedProvider.asStateFlow()

    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    fun toggleShield(enabled: Boolean) {
        _isShieldActive.value = enabled
        dnsCache.clear()
        DiagnosticLogger.i("PrivacyShield", if (enabled) "تم تفعيل درع الخصوصية وتشفير DoH بنجاح بدون خادم وسيط" else "تم إيقاف درع الخصوصية")
    }

    fun setProvider(provider: DnsProvider) {
        _selectedProvider.value = provider
        dnsCache.clear()
        DiagnosticLogger.i("PrivacyShield", "تم تغيير مزود التشفير إلى: ${provider.displayName}")
    }

    fun getSecureDns(client: () -> OkHttpClient): Dns {
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!_isShieldActive.value) {
                    return Dns.SYSTEM.lookup(hostname)
                }

                // Check in-memory fast cache
                dnsCache[hostname]?.let { return it }

                return try {
                    val provider = _selectedProvider.value
                    val url = "${provider.dohEndpoint}?name=$hostname&type=A"
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/dns-json")
                        .build()

                    val response = client().newCall(request).execute()
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        val json = JSONObject(body)
                        val answers = json.optJSONArray("Answer")
                        val addresses = mutableListOf<InetAddress>()
                        if (answers != null) {
                            for (i in 0 until answers.length()) {
                                val item = answers.getJSONObject(i)
                                if (item.optInt("type") == 1) { // Type A (IPv4)
                                    val ip = item.optString("data")
                                    if (ip.isNotEmpty()) {
                                        addresses.add(InetAddress.getByName(ip))
                                    }
                                }
                            }
                        }
                        if (addresses.isNotEmpty()) {
                            dnsCache[hostname] = addresses
                            DiagnosticLogger.d("PrivacyShield", "DoH Resolved $hostname -> ${addresses.first().hostAddress}")
                            return addresses
                        }
                    }
                    // Fallback to system DNS
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: Exception) {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }
    }
}
