# Database & JPA performance guide

How this app keeps PostgreSQL + Hibernate fast, how to measure it yourself, and what to do when it grows.

## 1. Measure first

| Tool | How | What it tells you |
|---|---|---|
| Access log | always on (`vchat.access` logger) | `GET /api/conversations -> 200 in 46 ms`, with a `requestId` per line |
| Service timings (Spring AOP) | `MonitoringAspect`; WARN when a call takes > `SLOW_CALL_MS` (500) | Which service method is slow. Also a Micrometer timer at `/actuator/metrics/vchat.service` |
| Hibernate slow-query log | always on, `SLOW_QUERY_MS` (200 ms) | Any single SQL statement slower than the threshold |
| SQL profile | `mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=sql` | Every statement with bound parameters, plus per-session metrics ("executing **N** JDBC statements"). Use it to spot **N+1** |
| `pg_stat_statements` | `docker exec -it vchat-db-1 psql -U vchat -d vchat` then `CREATE EXTENSION IF NOT EXISTS pg_stat_statements;` | The most expensive queries over time (query below) |
| `auto_explain` | on in both docker-compose files (dev and `deploy/`) | Postgres logs the actual plan of any statement over 500 ms |
| `EXPLAIN (ANALYZE, BUFFERS)` | `perf/explain.sql` (below) | Why one specific query is slow |

```sql
-- Top 10 queries by total time (needs pg_stat_statements)
SELECT round(total_exec_time) AS total_ms, calls, round(mean_exec_time, 2) AS mean_ms, rows, left(query, 120) AS query
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;
```

## 2. Reproduce with realistic data

Run these against a **separate** `vchat_perf` database, never your dev data:

```powershell
docker exec vchat-db-1 createdb -U vchat vchat_perf
Get-Content backend/src/main/resources/db/migration/V1__init.sql | docker exec -i vchat-db-1 psql -U vchat -d vchat_perf
Get-Content backend/perf/seed.sql | docker exec -i vchat-db-1 psql -U vchat -d vchat_perf      # ~5 min: 20k users, 50k chats, 2.3M messages
Get-Content backend/perf/explain.sql | docker exec -i vchat-db-1 psql -U vchat -d vchat_perf   # "before"
Get-Content backend/src/main/resources/db/migration/V2__oauth_and_indexes.sql | docker exec -i vchat-db-1 psql -U vchat -d vchat_perf
Get-Content backend/perf/explain.sql | docker exec -i vchat-db-1 psql -U vchat -d vchat_perf   # "after"
```

Full outputs from the run on 2026-10-12 are in `explain-before-v2.txt` and `explain-after-v2.txt`.

| Query | V1 schema | With V2 indexes | Fix |
|---|---|---|---|
| People search `lower(name) LIKE '%term%'` | 40.5 ms, 19,989 rows filtered | **0.56 ms** | `pg_trgm` GIN indexes (a B-tree can't serve a leading `%`) |
| Delete a user (`message.sender_id ON DELETE SET NULL`) | 300 ms, FK trigger scans 2.3M rows | **7 ms** | Postgres doesn't index FK columns; partial index `WHERE sender_id IS NOT NULL` |
| Chat list memberships | 36 ms, seq scan (99,700 rows removed) | **14.6 ms** | `idx_member_user (user_id) INCLUDE (...)` |
| Newest message per chat | 22.7 ms | **14.8 ms** | index-only scan + correlated `LIMIT 1` probe per chat |
| History page (50 msgs, 900 deep) | keyset 0.29 ms vs `OFFSET` 2.06 ms | — | keyset pagination; `OFFSET` gets linearly slower the further back you scroll |
| Unread counts (300 chats) | 68 ms | 62 ms | Bounded by counting ~128 rows per chat. Next step: a denormalized counter (below) |

## 3. What the code does (and why)

**N+1 prevention**
- The chat list used to run 3 queries per chat, plus one per member user: about 49 statements for 12 chats. It now runs **4 statements however many chats you have** (`ChatService.list`: `join fetch` memberships, members batched with `IN`, latest message per chat, `GROUP BY` unread counts). This was measured with Hibernate statistics.
- `hibernate.default_batch_fetch_size=50`: any remaining lazy association loads in batches of 50 ids, not one row at a time.
- `spring.jpa.open-in-view=false`: lazy loading can't happen silently while JSON is rendered. DTOs are built inside the transaction.
- `fail_on_pagination_over_collection_fetch=true`: stops Hibernate from silently paginating a collection fetch in memory.
- Message history `join fetch`es the sticker; ids of lazy relations (`sender`, `replyTo`) are read from the proxy without loading it.

**Hibernate / JDBC**
- JDBC batching (`batch_size=50`, `order_inserts`) plus pgjdbc `reWriteBatchedInserts`. This works because ids are app-assigned UUIDs: `IDENTITY` ids disable batching.
- **UUIDv7** primary keys (time-ordered). Random v4 UUIDs scatter inserts across the whole PK B-tree. v7 appends at the right edge, giving fewer page splits and a hotter cache.
- `in_clause_parameter_padding`: `IN (?,?,?)` lists are padded to powers of two, so Postgres and Hibernate reuse prepared plans.
- `@Modifying` single-statement `UPDATE`s for hot writes (last-seen) instead of load, dirty-check, then flush.
- `@Transactional(readOnly = true)` on reads: Hibernate skips dirty checking and the flush.
- HikariCP: small pool (10). Postgres throughput peaks at about 2× CPU cores of active connections, and more connections just queue inside the database.
- Java 21 virtual threads (`spring.threads.virtual.enabled`): blocking calls (DB, Claude, TTS) don't pin platform threads.

**Caching: three layers, each where it fits**
| Layer | What | Why not elsewhere |
|---|---|---|
| Hibernate 2nd-level cache (**Ehcache**, `ehcache.xml`) | Stickers, sticker packs, avatar models, bot characters | Read-mostly reference data. In-JVM, nanosecond hits |
| **Redis** (Spring Cache) | TTS audio (7 days), voice catalogues, provider health | Shared by every instance, survives restarts; TTS calls cost money and time |
| **Redis** (direct) | Presence (`presence:{user}` sets), rate-limit counters | Must be global across instances |

Chat data (messages, conversations, unread counts) is deliberately **not** cached. It changes on every message, and keeping a cache consistent across instances costs more than the indexed queries above.

**Postgres settings**
- Autovacuum and analyze run sooner on `message` (5% / 2% instead of 20% / 10%), so planner statistics stay fresh as the table grows.
- docker-compose (dev and `deploy/`): `pg_stat_statements`, `auto_explain`, `log_min_duration_statement`, `track_io_timing`.

## 4. When it grows: next steps, in order

1. **Denormalized unread counter.** Add `conversation_member.unread_count`, increment it when a message is inserted (in the same transaction, or with a trigger) and zero it on read. That makes unread counts O(1) instead of counting rows.
2. **Read replica.** Route `@Transactional(readOnly = true)` work to a read replica (any managed Postgres offers one) with `AbstractRoutingDataSource`.
3. **Partition `message` by month** (`PARTITION BY RANGE (created_at)`) when it passes about 100M rows or you need cheap retention (drop old partitions instead of `DELETE`). Caveat: on a partitioned table every unique key must include the partition key, so the PK becomes `(id, created_at)`. The FKs `message_reaction.message_id` and `message.reply_to_id` would then need `created_at` too, or be enforced in the app. Use `pg_partman` to create partitions automatically. Not worth it at the current size.
4. **Sharding** by `conversation_id` (all of a chat's messages on one shard): Citus (a Postgres extension) keeps SQL and JPA working. At "Discord scale" teams move messages to a wide-column store (Cassandra or ScyllaDB) keyed by `(conversation_id, time bucket)`. This app is far from that point. A single tuned Postgres handles millions of users' worth of chat metadata.

## 5. Why PostgreSQL rather than MariaDB

Both are fine relational databases, but this app relies on things Postgres does better or MariaDB lacks: `pg_trgm` trigram indexes for `%term%` search, partial indexes (`WHERE ... IS NOT NULL`), `INCLUDE` covering indexes, a native `uuid` type, `timestamptz`, `pg_stat_statements` and `auto_explain`, and first-class support from every managed database provider. Switching would remove features without a performance gain. All the techniques above (indexing, EXPLAIN, N+1 avoidance, L2 cache, batching, partitioning) apply equally to MariaDB if you ever need it: use `EXPLAIN ANALYZE` / `ANALYZE FORMAT=JSON` and the slow query log there.
