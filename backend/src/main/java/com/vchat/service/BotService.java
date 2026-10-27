package com.vchat.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlock;
import com.vchat.config.VchatProperties;
import com.vchat.domain.BotCharacter;
import com.vchat.domain.Conversation;
import com.vchat.domain.Message;
import com.vchat.dto.Dtos.BotSpeech;
import com.vchat.dto.Dtos.MessageDto;
import com.vchat.dto.Dtos.TypingEvent;
import com.vchat.repo.ConversationRepo;
import com.vchat.repo.MessageRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The VTuber-style chat bot. Each reply starts with an [emotion:lang] tag that drives the avatar's
 * expression and picks the TTS voice language; the tag is stripped before the text is stored.
 */
@Service
public class BotService {
    private static final Logger log = LoggerFactory.getLogger(BotService.class);
    private static final Pattern TAG = Pattern.compile("^\\s*\\[(\\w+)(?::(\\w{2}))?]\\s*");
    private static final int HISTORY = 40;
    /** Models that accept the server-side refusal fallback. Claude Haiku 5.5 has none, so it must not be sent there. */
    private static final Set<String> SERVER_SIDE_FALLBACK = Set.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1");

    private final AnthropicClient claude;
    private final ConversationRepo conversations;
    private final MessageRepo messages;
    private final ChatService chat;
    private final EventPublisher events;
    private final TransactionTemplate tx;
    private final VchatProperties.Ai ai;

    public BotService(AnthropicClient claude, ConversationRepo conversations, MessageRepo messages, ChatService chat,
                      EventPublisher events, TransactionTemplate tx, VchatProperties props) {
        this.claude = claude; this.conversations = conversations; this.messages = messages; this.chat = chat;
        this.events = events; this.tx = tx; this.ai = props.ai();
    }

    private record Turn(boolean assistant, String text) {}
    private record Context(BotCharacter bot, List<Turn> turns) {}

    /** One bot turn. Called by LocalBotDispatcher (@Async, in-process) or KafkaBotDispatcher (durable job). */
    public void reply(UUID convId) {
        Context ctx = tx.execute(s -> loadContext(convId));
        if (ctx == null || ctx.turns().isEmpty()) return;
        BotCharacter bot = ctx.bot();

        events.toConversation(convId, "typing", new TypingEvent(null, true));
        String raw;
        try {
            raw = ask(bot, ctx.turns());
        } catch (AnthropicServiceException ex) {
            log.warn("Claude call failed for conversation {}: {}", convId, ex.getMessage());
            raw = "[sad] Sorry, I can't answer right now. Please try again in a moment.";
        } finally {
            events.toConversation(convId, "typing", new TypingEvent(null, false));
        }

        String emotion = "neutral";
        String lang = bot.getLanguage();
        Matcher m = TAG.matcher(raw);
        if (m.find()) {
            emotion = m.group(1).toLowerCase();
            if (m.group(2) != null) lang = m.group(2).toLowerCase();
            raw = raw.substring(m.end());
        }
        MessageDto saved = chat.saveBotMessage(convId, raw.strip());
        events.toConversation(convId, "bot.speak",
                new BotSpeech(saved.id(), saved.content(), emotion, lang, bot.getTtsProvider(), bot.getVoiceId()));
    }

    private Context loadContext(UUID convId) {
        Conversation c = conversations.findById(convId).orElse(null);
        if (c == null || c.getBot() == null) return null;
        BotCharacter bot = c.getBot();
        bot.getName(); // initialize lazy proxy inside the transaction
        List<Message> recent = new ArrayList<>(messages.page(convId, Instant.now().plusSeconds(1), PageRequest.of(0, HISTORY)));
        Collections.reverse(recent);
        List<Turn> turns = recent.stream()
                .filter(x -> !x.isRecalled())
                .map(x -> new Turn(x.isFromBot(), describe(x)))
                .filter(t -> t.text() != null && !t.text().isBlank())
                .collect(Collectors.toList());
        return new Context(bot, turns);
    }

    private static String describe(Message m) {
        return switch (m.getType()) {
            case TEXT -> m.getContent();
            case STICKER -> "(sends a sticker: " + (m.getSticker() == null ? "unknown" : m.getSticker().getName()) + ")";
            case IMAGE -> "(sends an image)" + (m.getContent() == null ? "" : " " + m.getContent());
            case FILE -> "(sends a file)";
            case AUDIO -> "(sends a voice message)";
            case SYSTEM -> null;
        };
    }

    private String ask(BotCharacter bot, List<Turn> turns) {
        String system = """
                You are %s, a virtual streamer-style character chatting with a fan in a messaging app.
                Character sheet:
                %s

                Begin every reply with exactly one tag of the form [emotion:lang], where emotion is one of
                neutral, happy, sad, angry, surprised, shy and lang is the ISO 639-1 code of the language you
                reply in (vi, en, zh, ja, ko, es, ru). The tag drives your avatar's face and your voice.
                Reply in the language the user writes in; if unclear, use "%s".
                Keep replies short and conversational (1-3 sentences) because they are read aloud.
                Do not use markdown, emoji shortcodes or stage directions.
                """.formatted(bot.getName(), bot.getPersona(), bot.getLanguage());

        // The bot's own model while it is still offered (vchat.ai.models), else the default
        String model = ai.resolve(bot.getModel());
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                // Chat replies are short and latency-sensitive: low effort keeps them fast and cheap.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
        if (SERVER_SIDE_FALLBACK.contains(model)) {
            // Server-side refusal fallback: if a safety classifier declines, the API retries on a suitable model.
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        // Merge consecutive same-role turns and make sure the transcript starts with the user.
        List<Turn> merged = new ArrayList<>();
        for (Turn t : turns) {
            if (merged.isEmpty() && t.assistant()) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1).assistant() == t.assistant()) {
                Turn prev = merged.remove(merged.size() - 1);
                merged.add(new Turn(t.assistant(), prev.text() + "\n" + t.text()));
            } else {
                merged.add(t);
            }
        }
        if (merged.isEmpty() || merged.get(merged.size() - 1).assistant()) return "[neutral] ...";
        for (Turn t : merged) {
            if (t.assistant()) b.addAssistantMessage(t.text());
            else b.addUserMessage(t.text());
        }

        com.anthropic.models.messages.Message res = claude.messages().create(b.build());
        boolean refused = res.stopReason().map(r -> "refusal".equals(r.toString())).orElse(false);
        if (refused) return "[shy] Let's talk about something else, okay?";
        return res.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }
}
