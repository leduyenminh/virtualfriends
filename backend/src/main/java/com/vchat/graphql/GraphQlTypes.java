package com.vchat.graphql;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shapes that exist only in the GraphQL schema (REST returns maps for these). */
final class GraphQlTypes {
    private GraphQlTypes() {}

    record ReadReceipt(UUID userId, Instant readAt) {}

    record Reaction(String emoji, int count, List<UUID> userIds) {}

    record Presence(UUID userId, boolean online, Instant lastSeenAt) {}
}
