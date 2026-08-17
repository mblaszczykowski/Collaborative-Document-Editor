package dev.collabedit.app.ws;

import com.fasterxml.jackson.databind.JsonNode;
import dev.collabedit.app.config.CollabEditProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class FrameChunks {
    private FrameChunks() {
    }

    private static final int ENTRY_OVERHEAD_BYTES = 32;

    static int budgetBytes(CollabEditProperties properties) {
        return properties.websocket().maxMessageSizeBytes() / 2;
    }

    static List<List<JsonNode>> byBytes(List<JsonNode> encoded, int budget) {
        List<List<JsonNode>> chunks = new ArrayList<>();
        List<JsonNode> current = new ArrayList<>();
        int bytes = 0;

        for (JsonNode node : encoded) {
            int size = node.toString().getBytes(StandardCharsets.UTF_8).length
                + ENTRY_OVERHEAD_BYTES;
            if (!current.isEmpty() && bytes + size > budget) {
                chunks.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(node);
            bytes += size;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
