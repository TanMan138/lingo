package com.tanman.chattranslator.client.translation;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class LanguageDetector {

    private static final double CONFIDENCE_THRESHOLD = 0.5;

    // Lingua normalizes confidence values relative to the best-scoring language for
    // the given input, so the top entry is effectively always ~1.0 regardless of how
    // short or ambiguous the text is. See design notes in earlier revisions: short
    // English chat slang is routinely misdetected when all ~75 languages compete.
    private static final int MIN_TEXT_LENGTH = 15;

    /**
     * Languages we both (a) care about translating and (b) typically have a
     * {@code Xenova/opus-mt-*-en} model for. Detecting against all Lingua languages
     * produced noise like Tagalog/Latin/Shona on English chat and then wasted
     * downloads on 401s.
     */
    private static final Set<Language> SUPPORTED = Set.of(
            Language.ENGLISH,
            Language.FRENCH,
            Language.GERMAN,
            Language.SPANISH,
            Language.RUSSIAN,
            Language.JAPANESE,
            Language.CHINESE,
            Language.KOREAN,
            Language.PORTUGUESE,
            Language.ITALIAN,
            Language.DUTCH,
            Language.POLISH,
            Language.UKRAINIAN,
            Language.ARABIC,
            Language.TURKISH,
            Language.SWEDISH,
            Language.CZECH,
            Language.FINNISH,
            Language.HUNGARIAN,
            Language.ROMANIAN,
            Language.GREEK,
            Language.HINDI,
            Language.INDONESIAN,
            Language.VIETNAMESE,
            Language.THAI
    );

    /**
     * Languages that share Latin script with English. Lingua's n-gram model can't tell
     * "this is actually French" from "this is Russian/Hindi/Arabic typed in Latin
     * letters (romanized) instead of its native script" — both look like Latin text to
     * it, and it will confidently pick one of these even when wrong. Callers with a
     * more capable remote detector should treat a guess from this set as unreliable
     * rather than trusting it outright.
     */
    private static final Set<Language> LATIN_SCRIPT = Set.of(
            Language.FRENCH,
            Language.GERMAN,
            Language.SPANISH,
            Language.PORTUGUESE,
            Language.ITALIAN,
            Language.DUTCH,
            Language.POLISH,
            Language.TURKISH,
            Language.SWEDISH,
            Language.CZECH,
            Language.FINNISH,
            Language.HUNGARIAN,
            Language.ROMANIAN,
            Language.INDONESIAN,
            Language.VIETNAMESE
    );

    private static final Set<String> LATIN_SCRIPT_ISO_CODES = LATIN_SCRIPT.stream()
            .map(language -> language.getIsoCode639_1().toString().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    /** True when {@code isoCode} is a guess this detector can't distinguish from romanized text. */
    public static boolean isLatinScriptGuess(String isoCode) {
        return LATIN_SCRIPT_ISO_CODES.contains(isoCode);
    }

    private final com.github.pemistahl.lingua.api.LanguageDetector delegate;

    public LanguageDetector() {
        this.delegate = LanguageDetectorBuilder.fromLanguages(SUPPORTED.toArray(Language[]::new))
                .build();
    }

    public Optional<String> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (text.trim().length() < MIN_TEXT_LENGTH) {
            return Optional.empty();
        }

        Map<Language, Double> confidences = delegate.computeLanguageConfidenceValues(text);
        if (confidences.isEmpty()) {
            return Optional.empty();
        }

        Map.Entry<Language, Double> top = confidences.entrySet().iterator().next();
        Language topLanguage = top.getKey();
        double topConfidence = top.getValue();

        if (topLanguage == Language.ENGLISH || topLanguage == Language.UNKNOWN) {
            return Optional.empty();
        }
        if (topConfidence < CONFIDENCE_THRESHOLD) {
            return Optional.empty();
        }
        if (!SUPPORTED.contains(topLanguage)) {
            return Optional.empty();
        }

        return Optional.of(topLanguage.getIsoCode639_1().toString().toLowerCase(Locale.ROOT));
    }
}
