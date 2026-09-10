package io.github.git13166956007.dsh.model;

import io.github.git13166956007.dsh.agent.ChatMessage;
import io.github.git13166956007.dsh.agent.ToolCall;
import java.util.List;

/** Counts model input tokens. Providers can replace this with an exact tokenizer. */
@FunctionalInterface
public interface ModelTokenizer {
    int count(ChatMessage message);

    default int count(List<ChatMessage> messages) {
        return messages.stream().mapToInt(this::count).sum();
    }

    static ModelTokenizer approximate() {
        return ApproximateModelTokenizer.INSTANCE;
    }

    final class ApproximateModelTokenizer {
        private static final ModelTokenizer INSTANCE = message -> {
            int characters = message.content() == null ? 0
                    : message.content().codePointCount(0, message.content().length());
            for (ToolCall call : message.toolCalls()) {
                characters += call.name() == null ? 0 : call.name().length();
                characters += call.arguments() == null ? 0 : call.arguments().toString().length();
            }
            return Math.max(1, (characters + 3) / 4);
        };

        private ApproximateModelTokenizer() {
        }

    }
}
