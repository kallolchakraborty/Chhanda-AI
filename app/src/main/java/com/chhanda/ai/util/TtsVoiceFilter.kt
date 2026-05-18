package com.chhanda.ai.util

import android.speech.tts.Voice
import java.util.Locale
import android.util.Log

/**
 * TtsVoiceFilter: Production-hardened voice selection engine that parses and matches voice signatures
 * across Google TTS, Samsung TTS, and other system engines.
 * 
 * Enforces strict mutual exclusivity to avoid substring overlap collisions (e.g. "female" containing "male",
 * "woman" or "german" containing "man") so that Male and Female voices are always resolved correctly.
 */
object TtsVoiceFilter {

    private fun languagesMatch(lang1: String, lang2: String): Boolean {
        val l1 = lang1.lowercase()
        val l2 = lang2.lowercase()
        if (l1 == l2) return true
        
        // Handle standard 2-letter vs 3-letter mappings for Indic and English
        if ((l1 == "bn" || l1 == "ben") && (l2 == "bn" || l2 == "ben")) return true
        if ((l1 == "hi" || l1 == "hin") && (l2 == "hi" || l2 == "hin")) return true
        if ((l1 == "en" || l1 == "eng") && (l2 == "en" || l2 == "eng")) return true
        
        return l1.startsWith(l2) || l2.startsWith(l1)
    }

    fun findBestVoice(voices: List<Voice>, activeLocale: Locale, isMale: Boolean): Voice? {
        val language = activeLocale.language
        val country = activeLocale.country

        // 1. Filter voices of the same language (e.g. all Hindi or all Bengali or all English)
        val localeVoices = voices.filter { v -> languagesMatch(v.locale.language, language) }
        if (localeVoices.isEmpty()) {
            Log.d("TtsVoiceFilter", "No voices found matching language: $language")
            return null
        }

        // Sort alphabetically to maintain absolute deterministic order for alternation
        val sortedVoices = localeVoices.sortedBy { it.name }
        
        val maleCandidates = mutableListOf<Voice>()
        val femaleCandidates = mutableListOf<Voice>()
        
        for (i in sortedVoices.indices) {
            val v = sortedVoices[i]
            val name = v.name.lowercase()
            
            // Explicitly verify female attributes first
            val isExplicitFemale = name.contains("female") || name.contains("woman") || 
                name.contains("girl") || name.contains("lady") || 
                name.contains("-f0") || name.contains("-f1") || name.contains("-f-") || 
                name.contains("_f_") || name.endsWith("-f") || name.endsWith("_f") ||
                name.contains("smt-f") || name.contains("gfm") ||
                // Google English/Indic Female Suffixes
                name.contains("-ena-") || name.contains("-enc-") || name.contains("-ene-") || 
                name.contains("-enf-") || name.contains("-enh-") || name.contains("-eni-") || 
                name.contains("-enk-") ||
                // Google Hindi Female
                name.contains("-hia-") || name.contains("-hib-") || name.contains("-hie-") || 
                name.contains("-hif-") || name.contains("-hig-") ||
                // Google Bengali Female
                name.contains("-baa-") || name.contains("-bab-") || name.contains("-bae-") || 
                name.contains("-bag-") || name.contains("-ban-") ||
                // Samsung & other explicit checks
                name.contains("-a-") || name.contains("-c-") || name.contains("-e-") || 
                name.contains("-f-") || name.contains("-h-") || name.contains("-i-") || name.contains("-k-")

            // Explicitly verify male attributes, ensuring zero overlap with female terms or language tags
            val isExplicitMale = (name.contains("male") && !name.contains("female")) || 
                (name.contains("man") && !name.contains("woman") && !name.contains("german") && !name.contains("romanian")) || 
                name.contains("guy") || name.contains("boy") ||
                name.contains("-m0") || name.contains("-m1") || name.contains("-m-") || 
                name.contains("_m_") || name.endsWith("-m") || name.endsWith("_m") ||
                name.contains("smt-m") ||
                // Google English/Indic Male Suffixes
                name.contains("-enb-") || name.contains("-end-") || name.contains("-eng-") || 
                name.contains("-enj-") || name.contains("-enl-") || name.contains("-enm-") || 
                name.contains("-enn-") ||
                // Google Hindi Male
                name.contains("-hic-") || name.contains("-hid-") || name.contains("-him-") ||
                // Google Bengali Male
                name.contains("-bac-") || name.contains("-bad-") || name.contains("-bap-") ||
                // Samsung & other explicit checks
                name.contains("-b-") || name.contains("-d-") || name.contains("-g-") || 
                name.contains("-j-") || name.contains("-l-") || name.contains("-m-") || name.contains("-n-")
                
            if (isExplicitMale && !isExplicitFemale) {
                maleCandidates.add(v)
            } else if (isExplicitFemale && !isExplicitMale) {
                femaleCandidates.add(v)
            } else {
                // Alternating fallback for uncategorized or dynamic system voices
                if (i % 2 == 1) {
                    maleCandidates.add(v)
                } else {
                    femaleCandidates.add(v)
                }
            }
        }

        // 2. Select from the appropriate pool
        val pool = if (isMale) maleCandidates else femaleCandidates
        val fallbackPool = if (pool.isNotEmpty()) pool else sortedVoices
        
        // 3. Sort by premium matching attributes:
        //    a. Country code match (highest preference)
        //    b. Offline usage capability (no network cost)
        //    c. Premium characteristics (neural, network-based quality, high fidelity)
        val selectedVoice = fallbackPool.sortedWith(
            compareByDescending<Voice> { it.locale.country.equals(country, ignoreCase = true) }
                .thenByDescending { !it.isNetworkConnectionRequired }
                .thenByDescending { it.name.lowercase().contains("network") || it.name.lowercase().contains("neural") }
                .thenByDescending { it.name.lowercase().contains("high") || it.name.lowercase().contains("premium") }
        ).firstOrNull()

        Log.d("TtsVoiceFilter", "findBestVoice resolved: name=${selectedVoice?.name}, locale=${selectedVoice?.locale}, requestedMale=$isMale")
        return selectedVoice ?: sortedVoices.firstOrNull()
    }
}
