package com.example.data.privacy

import android.net.Uri
import com.example.data.diagnostics.DiagnosticLogger

object AdBlockManager {
    var isEnabled: Boolean = true
    var blockedAdsCount: Long = 0L

    private val blockedHostSuffixes = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "popads.net",
        "popcash.net",
        "adsterra.com",
        "propellerads.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.com",
        "criteo.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "chartboost.com",
        "inmobi.com",
        "ironsrc.com",
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "yandex.ru/ads",
        "an.yandex.ru"
    )

    fun shouldBlockUrl(url: String): Boolean {
        if (!isEnabled) return false
        return try {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false
            val isBlocked = blockedHostSuffixes.any { host == it || host.endsWith(".$it") }
            if (isBlocked) {
                blockedAdsCount++
                DiagnosticLogger.d("AdBlocker", "تم حظر إعلان/تتبع من: $host")
            }
            isBlocked
        } catch (_: Exception) {
            false
        }
    }

    val cosmeticCssInjection: String = """
        (function() {
            var css = `
                .adsbygoogle, [id*='google_ads'], [class*='ad-container'],
                [class*='ad-banner'], [id*='ad-wrapper'], [class*='ad_wrapper'],
                iframe[src*='doubleclick'], iframe[src*='googlesyndication'],
                .ad-slot, .ad-box, .advertisement, [aria-label*='Advertisement'],
                .sponsored-post, [data-ad-client], .native-ad-unit {
                    display: none !important;
                    visibility: hidden !important;
                    height: 0 !important;
                    width: 0 !important;
                    pointer-events: none !important;
                    opacity: 0 !important;
                }
            `;
            var style = document.createElement('style');
            style.type = 'text/css';
            style.appendChild(document.createTextNode(css));
            if (document.head) {
                document.head.appendChild(style);
            } else if (document.body) {
                document.body.appendChild(style);
            }
        })();
    """.trimIndent()
}
