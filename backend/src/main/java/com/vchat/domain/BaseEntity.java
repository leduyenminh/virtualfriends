package com.vchat.domain;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.security.SecureRandom;
import java.util.UUID;

@MappedSuperclass
public abstract class BaseEntity {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    protected UUID id;

    @PrePersist
    void assignId() {
        if (id == null) id = uuidV7();
    }

    public UUID getId() { return id; }

    /**
     * RFC 9562 UUIDv7: 48-bit millisecond timestamp + random bits. Unlike random v4 ids, new rows land at the
     * right-hand edge of the primary-key B-tree, so inserts don't split random index pages (less write
     * amplification, better cache locality, ~ creation-time ordering for free).
     */
    static UUID uuidV7() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
