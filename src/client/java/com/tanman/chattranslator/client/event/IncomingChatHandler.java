package com.tanman.chattranslator.client.event;

import com.tanman.chattranslator.ChatTranslator;
import com.tanman.chattranslator.client.config.TranslatorConfig;
import com.tanman.chattranslator.client.state.TranslationState;
import com.tanman.chattranslator.client.translation.LanguageDetector;
import com.tanman.chattranslator.client.translation.ProtectedSpans;
import com.tanman.chattranslator.client.translation.TranslationResult;
import com.tanman.chattranslator.client.translation.backend.TranslationService;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Two ways to read foreign chat:
 *
 * <ul>
 *   <li><b>Auto</b> (default): a line the mod can already translate — remote backend,
 *       or an on-device pair that is on disk — gets its English appended inline.
 *       Nothing is downloaded on its own, so joining a busy server never triggers a
 *       surprise ~100 MB fetch.</li>
 *   <li><b>Hover</b>: hovering (or clicking) a line translates it on demand, which is
 *       also what downloads a pair the first time.</li>
 * </ul>
 *
 * <p>Tooltips are resolved live via {@link #liveTooltip} because Minecraft bakes
 * {@link net.minecraft.network.chat.Style} into chat line buffers at add-time. Inline
 * text has the same problem, so appending is followed by a chat rescale.
 */
public final class IncomingChatHandler {

    public static final Identifier CLICK_ID =
            Identifier.fromNamespaceAndPath(ChatTranslator.MOD_ID, "translate");

    private static final String TARGET_LANGUAGE = "en";

    private static final String HINT = "Hover to translate → English (Lingo)";

    /**
     * Trailing handle that carries our hover/click. Servers commonly wrap a whole chat
     * line in their own hover/click, and a sibling style wins over the parent's for
     * every field it sets — so an event on the wrapper alone is unreachable on those
     * lines. This sibling is ours, so it always resolves to us.
     */
    private static final String HANDLE = " ⇄";

    private static final long ON_DEVICE_TIMEOUT_SECONDS = 90;
    private static final long REMOTE_TIMEOUT_SECONDS = 30;

    private static final int MAX_TRANSLATION_FAILURES = 3;

    /** Cap pending click targets so a long session cannot grow without bound. */
    private static final int MAX_PENDING = 200;

    private static final Executor WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-translator-incoming");
        thread.setDaemon(true);
        return thread;
    });

    private static final Set<String> FAILED_PAIRS = ConcurrentHashMap.newKeySet();

    private static final Map<String, Integer> TRANSLATION_FAILURES = new ConcurrentHashMap<>();

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    private static volatile TranslationState state;
    private static volatile LanguageDetector detector;
    private static volatile TranslationService translationService;
    private static volatile TranslatorConfig config;

    private enum Status {
        READY,
        RUNNING,
        DONE
    }

    private record Pending(
            MutableComponent display,
            String body,
            AtomicReference<Status> status,
            AtomicReference<String> tooltip,
            /** True while the current run started by itself rather than from a hover. */
            AtomicBoolean auto
    ) {
    }

    private IncomingChatHandler() {
    }

    public static void register(
            TranslationState translationState,
            LanguageDetector languageDetector,
            TranslationService service,
            TranslatorConfig translatorConfig
    ) {
        state = translationState;
        detector = languageDetector;
        translationService = service;
        config = translatorConfig;

        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            if (overlay) {
                return message;
            }
            String rendered = message.getString();
            return prepare(message.copy(), ChatLineSplitter.split(rendered).body(), rendered);
        });

        ClientReceiveMessageEvents.ALLOW_CHAT.register(
                (message, signedMessage, sender, chatType, receptionTimestamp) -> {
                    try {
                        MutableComponent display = message.copy();
                        String body = signedMessage.signedBody().content();
                        display = prepare(display, body, message.getString());

                        Minecraft minecraft = Minecraft.getInstance();
                        if (minecraft.gui != null) {
                            MessageSignature signature = signedMessage.signature();
                            minecraft.gui.getChat().addPlayerMessage(display, signature, null);
                        }
                        return false;
                    } catch (Exception error) {
                        ChatTranslator.LOGGER.warn(
                                "Failed to prepare incoming chat message", error);
                        return true;
                    }
                });
    }

    /**
     * Live tooltip text for our translate click-ids. Used by {@code StyleMixin}
     * because baked chat styles never see later {@code setStyle} updates.
     */
    public static Optional<String> liveTooltip(ClickEvent event) {
        String id = payloadId(event);
        if (id == null) {
            return Optional.empty();
        }
        Pending pending = PENDING.get(id);
        if (pending == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.tooltip().get());
    }

    /**
     * Hover or click entry point from {@code ChatScreen} mixin.
     *
     * @return {@code true} if this was our click id (handled even if already running/done)
     */
    public static boolean requestTranslate(ClickEvent event) {
        String id = payloadId(event);
        if (id == null) {
            return event instanceof ClickEvent.Custom custom && CLICK_ID.equals(custom.id());
        }
        Pending pending = PENDING.get(id);
        if (pending == null) {
            return true;
        }
        if (!pending.status().compareAndSet(Status.READY, Status.RUNNING)) {
            return true;
        }
        // A hover is an explicit request: it may download a model, unlike an auto run.
        pending.auto().set(false);
        pending.tooltip().set("Translating…");
        WORKER.execute(() -> process(pending));
        return true;
    }

    private static String payloadId(ClickEvent event) {
        if (!(event instanceof ClickEvent.Custom custom) || !CLICK_ID.equals(custom.id())) {
            return null;
        }
        Optional<Tag> payload = custom.payload();
        if (payload.isEmpty() || !(payload.get() instanceof StringTag stringTag)) {
            return null;
        }
        return stringTag.value();
    }

    private static MutableComponent prepare(MutableComponent display, String body, String rendered) {
        if (!IncomingMessageFilter.shouldOffer(rendered, body, selfName())) {
            return display;
        }

        String id = UUID.randomUUID().toString();
        trimPendingIfNeeded();

        // Wrapper so an auto-translation can be appended later: the chat bakes a line
        // into render buffers when it is added, and only a rescale re-reads the
        // component — but it re-reads this same instance, siblings included.
        MutableComponent holder = Component.empty().append(display);
        Pending pending = new Pending(
                holder,
                body,
                new AtomicReference<>(Status.READY),
                new AtomicReference<>(HINT),
                new AtomicBoolean(false));
        PENDING.put(id, pending);

        HoverEvent hover = new HoverEvent.ShowText(Component.literal(HINT));
        ClickEvent click = new ClickEvent.Custom(CLICK_ID, Optional.of(StringTag.valueOf(id)));

        // On a plainly styled line the wrapper's style is inherited by every character,
        // so the whole line stays hoverable as before.
        holder.setStyle(holder.getStyle().withHoverEvent(hover).withClickEvent(click));
        holder.append(Component.literal(HANDLE)
                .withStyle(ChatFormatting.GRAY)
                .withStyle(style -> style.withHoverEvent(hover).withClickEvent(click)));

        maybeStartAuto(pending);
        return holder;
    }

    /**
     * Starts translation without waiting for a hover, but only when the player asked
     * for auto mode. {@code process} still bails out if the backend would have to
     * download a model first.
     */
    private static void maybeStartAuto(Pending pending) {
        if (config == null || !config.autoTranslateIncoming) {
            return;
        }
        if (!pending.status().compareAndSet(Status.READY, Status.RUNNING)) {
            return;
        }
        pending.auto().set(true);
        WORKER.execute(() -> process(pending));
    }

    private static String selfName() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.getUser() == null
                ? null
                : minecraft.getUser().getName();
    }

    private static void trimPendingIfNeeded() {
        if (PENDING.size() < MAX_PENDING) {
            return;
        }
        int toRemove = PENDING.size() - MAX_PENDING + 50;
        var iterator = PENDING.keySet().iterator();
        while (toRemove > 0 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
            toRemove--;
        }
    }

    /** Runs entirely on {@link #WORKER}. Must never throw into the executor. */
    private static void process(Pending pending) {
        String body = pending.body();
        boolean auto = pending.auto().get();
        try {
            ProtectedSpans.Masked masked = ProtectedSpans.mask(body);
            Optional<String> detected = detector.detect(ProtectedSpans.unwrap(body));
            String sourceLanguage;
            if (detected.isPresent()) {
                sourceLanguage = detected.get();

                // Only an explicit hover retargets outgoing chat — otherwise every passing
                // lobby message would change the language the player replies in. This uses
                // the raw guess even when it's a Latin-script one below, since "auto" isn't
                // a valid reply-target language.
                if (!auto) {
                    String detectedLanguage = sourceLanguage;
                    runOnClientThread(() -> state.onLanguageDetected(detectedLanguage));
                }

                // A Latin-script guess can't be trusted: Lingua can't tell real French
                // from Russian/Hindi/Arabic typed in Latin letters. Remote backends carry
                // their own, better-trained detector, so let them re-decide instead of
                // translating from a possibly wrong source language. On-device has no
                // such fallback and must run with the guess as-is.
                if (translationService.isRemoteBackend()
                        && LanguageDetector.isLatinScriptGuess(sourceLanguage)) {
                    sourceLanguage = "auto";
                }
            } else if (translationService.isRemoteBackend()) {
                // Local script/n-gram detection misses romanized text (e.g. Hindi typed
                // in Latin letters). Cloud backends and Ollama can still make a call on
                // it themselves, so hand off with an unresolved source instead of giving
                // up. On-device has no generic "auto" model, so it still bails below.
                sourceLanguage = "auto";
            } else {
                give(pending, "Couldn't detect a language (English or too short).");
                return;
            }

            if (translationService.requiresModelDownload()) {
                String pairKey = pairKey(sourceLanguage, TARGET_LANGUAGE);
                if (FAILED_PAIRS.contains(pairKey)) {
                    give(pending, "No translation model for " + sourceLanguage + ".");
                    return;
                }
            }

            if (auto && !translationService.canTranslateWithoutDownload(
                    sourceLanguage, TARGET_LANGUAGE)) {
                // Auto mode never starts a download by itself; hovering does.
                resetForHover(pending);
                return;
            }

            if (!auto) {
                pending.tooltip().set("Running translation…");
            }

            long timeoutSeconds = translationService.isRemoteBackend()
                    ? REMOTE_TIMEOUT_SECONDS
                    : ON_DEVICE_TIMEOUT_SECONDS;

            TranslationResult result = translationService
                    .translate(masked.text(), sourceLanguage, TARGET_LANGUAGE)
                    .get(timeoutSeconds, TimeUnit.SECONDS);

            if (!result.success()) {
                if (translationService.requiresModelDownload()) {
                    noteTranslationFailure(pairKey(sourceLanguage, TARGET_LANGUAGE), sourceLanguage);
                }
                give(pending, "Translation failed.");
                return;
            }
            if (translationService.requiresModelDownload()) {
                TRANSLATION_FAILURES.remove(pairKey(sourceLanguage, TARGET_LANGUAGE));
            }

            String english = masked.restore(result.translatedText());
            if (english.isBlank()
                    || english.equals(ProtectedSpans.unwrap(result.originalText()))) {
                give(pending, "Nothing to translate.");
                return;
            }

            if (auto) {
                appendInline(pending, english);
            } else {
                finish(pending, english);
            }
        } catch (TimeoutException timeout) {
            give(pending, "Translation timed out.");
        } catch (Exception error) {
            ChatTranslator.LOGGER.warn("Failed to translate incoming message", error);
            give(pending, "Translation error — see log.");
        }
    }

    /**
     * Report a non-result: a hover gets the reason in its tooltip, an auto run goes
     * quiet and leaves the line hoverable so the player can ask for it explicitly.
     */
    private static void give(Pending pending, String reason) {
        if (pending.auto().get()) {
            resetForHover(pending);
        } else {
            finish(pending, reason);
        }
    }

    private static void resetForHover(Pending pending) {
        pending.auto().set(false);
        pending.tooltip().set(HINT);
        pending.status().set(Status.READY);
    }

    /**
     * Shows the English after the original text instead of replacing it — that keeps
     * the sender's name, colours, and formatting intact.
     */
    private static void appendInline(Pending pending, String english) {
        pending.tooltip().set("Original: " + pending.body());
        pending.status().set(Status.DONE);
        runOnClientThread(() -> {
            Component translated = Component.literal(" → " + english)
                    .withStyle(ChatFormatting.GRAY);
            // Keep the translate handle last so it stays a stable target to hover.
            var siblings = pending.display().getSiblings();
            if (siblings.isEmpty()) {
                pending.display().append(translated);
            } else {
                siblings.add(siblings.size() - 1, translated);
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.gui != null) {
                // Lines are wrapped into render buffers when added; this re-reads them.
                minecraft.gui.getChat().rescaleChat();
            }
        });
    }

    private static String pairKey(String source, String target) {
        return source + "-" + target;
    }

    private static void finish(Pending pending, String tooltip) {
        pending.tooltip().set(tooltip);
        pending.status().set(Status.DONE);
    }

    private static void noteTranslationFailure(String pairKey, String sourceLanguage) {
        int failures = TRANSLATION_FAILURES.merge(pairKey, 1, Integer::sum);
        if (failures >= MAX_TRANSLATION_FAILURES) {
            FAILED_PAIRS.add(pairKey);
            ChatTranslator.LOGGER.warn(
                    "Giving up on the {}->{} model after {} consecutive translation failures;"
                            + " the cached model may be corrupt",
                    sourceLanguage, TARGET_LANGUAGE, failures);
        }
    }

    private static void runOnClientThread(Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(action);
        }
    }
}
