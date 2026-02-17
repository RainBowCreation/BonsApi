# Bonsapi - Bonsai Java Client

The official Java client library for [Bonsai](../../README.md), a high-performance distributed key-value database with query support.

## Overview

Bonsapi provides a strongly-typed, fluent API for interacting with Bonsai servers. It supports:

- **Multiple connection modes**: TCP (binary protocol), HTTP (RESTful api), or direct embedded mode
- **Connection pooling**: Round-robin with least-pending fallback and request pipelining
- **Safe/Unsafe write modes**: Choose between durability guarantees and maximum throughput
- **Query operations**: SQL-like filtering with AND/OR logic, sorting, pagination, field selection, bulk updates, and counting
- **Type safety**: Automatic serialization/deserialization with compile-time generics
- **Primitive fast-path**: Optimized encoding for String, Integer, Long, and Boolean types
- **Async-first API**: Every blocking method has a `*Async()` counterpart returning `BonsaiFuture<T>`
- **Optional client-side cache**: Local Caffeine cache for repeated reads (off by default)

## Quick Start

### Add Dependency

```gradle
repositories {
    maven { url = uri("https://repo.rainbowcreation.net") }
}
dependencies {
    implementation("net.rainbowcreation.bonsai:BonsApi:0.1.0-SNAPSHOT")
}
```

### Basic Usage

```java
import net.rainbowcreation.bonsai.*;
import net.rainbowcreation.bonsai.api.BonsApi;

// Get the Bonsai client singleton (to localhost 4533)
Bonsai bonsai = BonsApi.getBonsai();

// Get a database root, dbName
BonsaiRoot db = bonsai.getRoot("myapp");

// Get a typed table; use(<class>)
BonsaiTable<User> users = db.use(User.class);
//or; use(<tableName>)
BonsaiTable<User> users = db.use("User");

// K-V operations
users.set("user:123", new User("Alice", 25));     // Write (blocks until durable)
User user = users.get("user:123");                 // Read (returns null if missing)
Boolean exists = users.exists("user:123");         // Check existence
users.delete("user:123");                          // Delete

// Shutdown; close connection
BonsApi.shutdown();
```

### Minimal Entity

```java
public class User {
    private String name;
    private int age;

    public User(String name, int age) {
        this.name = name;
        this.age = age;
    }

    // Getters, setters and etc...
}
```

## Connection Modes

Bonsapi automatically selects the best available connection method in this order:

1. **ServiceLoader** (in-process) - If a Bonsai server implementation is on the classpath
2. **TCP Socket** (default) - Fast binary protocol
3. **HTTP** (can be enable in config) - RESTful api

## Configurations

Configure before first getBonsai() call. BonSapi is designed to connect with local Edge/Master server,

changing HOST other and `localhost` is possible but not recommend.

### TCP Connection

```java
BonsApi.HOST = "192.168.1.10";
BonsApi.TCP_PORT = 4533;

Bonsai bonsai = BonsApi.getBonsai();
```

### HTTP Connection

```java
BonsApi.HOST = "bonsai.example.com";
BonsApi.HTTP_PORT = 8080;

Bonsai bonsai = BonsApi.getBonsai();
```

### Direct Embedded Mode

If a Bonsai server implementation is on the classpath (via ServiceLoader), the client
connects in-process without any network overhead. This is useful for testing or
co-located deployments.

## API Reference

### Getting a Database Root

```java
// Get a database namespace
BonsaiRoot db = bonsai.getRoot("myapp");

// With secret (if authentication is configured)
BonsaiRoot db = bonsai.getRoot("myapp", "my-secret-key");

// List available roots
Set<String> roots = bonsai.getRoots();
```

### Getting a Table

```java
// Using class name as table name, safe mode (default)
BonsaiTable<User> users = db.use(User.class);

// Using class name, explicit safe mode
BonsaiTable<User> users = db.use(User.class, true);

// Using class name, unsafe mode (fire-and-forget writes)
BonsaiTable<User> users = db.use(User.class, false);

// Custom table name with explicit type and mode
BonsaiTable<User> users = db.use("users_v2", User.class, true);

// Untyped table (Object type)
BonsaiTable<Object> raw = db.use("raw_data");

// Untyped table with explicit safe/unsafe
BonsaiTable<Object> raw = db.use("raw_data", false);
```

### Supported Value Types

Bonsapi supports any serializable Java object, with optimized fast-path encoding for primitive types:

| Type | Encoding | Overhead | Notes |
|------|----------|----------|-------|
| `String` | Primitive fast-path (0xBF + UTF-8) | 3+ bytes | Length-prefixed UTF-8 |
| `Integer` | Primitive fast-path (0xBF + 4 bytes) | 6 bytes | Big-endian |
| `Long` | Primitive fast-path (0xBF + 8 bytes) | 10 bytes | Big-endian |
| `Boolean` | Primitive fast-path (0xBF + 1 byte) | 3 bytes | 0x00/0x01 |
| POJOs | Apache Fory binary serialization | ~50+ bytes | Class metadata included |
| `Map` | Fory serialization | Variable | Any Map implementation |
| `List` | Fory serialization | Variable | Any List implementation |
| `byte[]` | Raw bytes (no encoding) | 0 bytes | Pass-through |

```java
// Primitive types use optimized encoding automatically
BonsaiTable<String> strings = db.use("strings", String.class, true);
BonsaiTable<Integer> ints = db.use("ints", Integer.class, true);
BonsaiTable<Long> longs = db.use("longs", Long.class, true);
BonsaiTable<Boolean> bools = db.use("bools", Boolean.class, true);

// POJOs use Fory serialization
BonsaiTable<User> users = db.use(User.class);

// Maps and Lists
BonsaiTable<Map> maps = db.use("settings", Map.class, true);
```

### K-V Operations

#### SET - Write Data

```java
// Synchronous write (safe mode: blocks until written to RocksDB WAL on master server)
users.set("user:123", new User("Alice", 25));

// Async write (returns immediately, future completes when server acknowledges)
BonsaiFuture<Void> future = users.setAsync("user:123", user);

// Wait for async completion if needed
future.get();

// Or with timeout
future.get(Duration.ofSeconds(5));

// Fire-and-forget callback
future.then(ignored -> System.out.println("Write confirmed!"));
```

#### GET - Read Data

```java
// Synchronous read (thread blocked until response received)
User user = users.get("user:123");
if (user == null) {
    // Key doesn't exist
}

// Async read
BonsaiFuture<User> future = users.getAsync("user:123");
User user = future.get();

// Async with type casting
BonsaiFuture<String> nameFuture = users.getAsync("user:123", String.class);

// Transform result
BonsaiFuture<String> nameFuture = users.getAsync("user:123")
    .map(u -> u.getName());
```

#### DELETE - Remove Data

```java
// Synchronous delete
users.delete("user:123");

// Async delete
BonsaiFuture<Void> future = users.deleteAsync("user:123");
```

#### EXISTS - Check Key

```java
// Synchronous existence check
Boolean exists = users.exists("user:123");

// Async existence check
BonsaiFuture<Boolean> future = users.existsAsync("user:123");
```

### BonsaiFuture API

Every async operation returns a `BonsaiFuture<T>`, which wraps `CompletableFuture`:

```java
BonsaiFuture<User> future = users.getAsync("user:123");

// Blocking get (throws RuntimeException on timeout/error)
User user = future.get();

// Blocking get with timeout
User user = future.get(Duration.ofSeconds(5));

// Fire-and-forget callback (does not return a new future)
future.then(user -> System.out.println("Got: " + user));

// Chaining transformation (returns new BonsaiFuture)
BonsaiFuture<User> modified = future.then(user -> {
    user.setAge(user.getAge() + 1);
    return user;
});

// Map to different type
BonsaiFuture<String> nameFuture = future.map(User::getName);

// Create a pre-completed future
BonsaiFuture<User> ready = BonsaiFuture.completed(defaultUser);

// Access underlying CompletableFuture for advanced composition
CompletableFuture<User> cf = future.asCompletable();
```

## Query Operations

Bonsai supports SQL-like queries on fields annotated with `@BonsaiQuery`. Queries are
translated to parameterized SQL on the server side and executed against MySQL.

### Defining Queryable Entities

`@BonsaiQuery` can be applied at the class level, the field level, or both.

#### Class-Level Annotation

When `@BonsaiQuery` is applied to the class, all fields become non-indexed SQL columns.
This enables partial updates and selective field fetching on any field, but does not
create indexes (to avoid accidentally indexing all fields).

```java
@BonsaiQuery  // Class-level: all fields become non-indexed columns
class User {
    String name;     // SQL column, no index
    int age;         // SQL column, no index
    String phone;    // SQL column, no index
}
```

#### Field-Level Annotation

When `@BonsaiQuery` is applied to individual fields, those fields become indexed SQL
columns by default. Fields without the annotation are stored only in the `_data` blob.

```java
class User {
    @BonsaiQuery                          // Indexed VARCHAR(255) by default
    private String name;

    @BonsaiQuery                          // Indexed INT
    private int age;

    @BonsaiQuery(unique = true)           // Indexed with UNIQUE constraint
    private String email;

    @BonsaiQuery(type = "TEXT")           // Override SQL type
    private String bio;

    @BonsaiQuery(length = 50)            // VARCHAR(50) instead of default 255
    private String username;

    @BonsaiQuery(indexed = false)         // Stored as column but NOT indexed
    private String notes;

    @BonsaiIgnore                          // Excluded from persistence entirely
    private transient String cachedHash;

    private String password;               // Not queryable (stored in _data blob only)

    public User() {}  // No-arg constructor required
    // Getters, setters...
}
```

#### Mixed Class + Field Annotation (Best practices for queryable data)

Combine class-level and field-level annotations for selective indexing: all fields
get columns, but only the ones you specify get B-tree indexes.

```java
@BonsaiQuery  // Class-level: all fields -> non-indexed columns
class User {
    String name;              // Column, no index
    String phone;             // Column, no index

    @BonsaiQuery              // Field-level: indexed=true (default)
    int age;                  // Column, INDEXED

    @BonsaiQuery(unique=true)
    String email;             // Column, INDEXED + UNIQUE
}
```

#### @BonsaiQuery Annotation Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `indexed` | `true` | Create a B-tree index on this column in MySQL. Ignored on class-level annotations (always false). |
| `type` | `""` (auto) | Override SQL column type (e.g., `"TEXT"`, `"DECIMAL(10,2)"`) |
| `length` | `255` | VARCHAR length for String fields |
| `unique` | `false` | Add UNIQUE constraint to this column |

**Auto-detected SQL types:**

| Java Type | SQL Type |
|-----------|----------|
| `int` / `Integer` | `INT` |
| `long` / `Long` | `BIGINT` |
| `double` / `Double` | `DOUBLE` |
| `boolean` / `Boolean` | `BOOLEAN` |
| `String` | `VARCHAR(length)` |
| Complex types (List, Map, etc.) | Stored in `_data` BLOB |

### Basic Queries

```java
import net.rainbowcreation.bonsai.query.QueryOp;
import net.rainbowcreation.bonsai.query.SortOrder;
import static net.rainbowcreation.bonsai.query.SearchCriteria.where;

// Simple equality filter
List<User> activeUsers = users.find()
    .where("status", QueryOp.EQ, "active")
    .get();

// Shorthand equality (QueryOp.EQ is default)
List<User> activeUsers = users.find()
    .where("status", "active")
    .get();

// Range filter
List<User> adults = users.find()
    .where("age", QueryOp.GTE, 18)
    .get();

// String pattern matching
List<User> aliceFamily = users.find()
    .where("name", QueryOp.LIKE, "Alice%")
    .get();

// Set membership
List<User> targeted = users.find()
    .where("status", QueryOp.IN, List.of("active", "pending", "trial"))
    .get();

// String-based operator (alternative syntax)
List<User> results = users.find()
    .where("age", ">", 18)
    .get();

// Raw query string
List<User> results = users.find()
    .where("age > 18 AND status = 'active'")
    .get();
```

### Query Operators

| Operator | Symbol | SQL Equivalent | Example |
|----------|--------|---------------|---------|
| `QueryOp.EQ` | `=` | `= ?` | `.where("age", QueryOp.EQ, 25)` |
| `QueryOp.NEQ` | `!=` | `!= ?` | `.where("status", QueryOp.NEQ, "banned")` |
| `QueryOp.GT` | `>` | `> ?` | `.where("age", QueryOp.GT, 18)` |
| `QueryOp.GTE` | `>=` | `>= ?` | `.where("level", QueryOp.GTE, 10)` |
| `QueryOp.LT` | `<` | `< ?` | `.where("age", QueryOp.LT, 65)` |
| `QueryOp.LTE` | `<=` | `<= ?` | `.where("score", QueryOp.LTE, 100)` |
| `QueryOp.LIKE` | `LIKE` | `LIKE ?` | `.where("name", QueryOp.LIKE, "%alice%")` |
| `QueryOp.IN` | `IN` | `IN (?, ?, ...)` | `.where("status", QueryOp.IN, list)` |

### Complex Queries with SearchCriteria

For AND/OR logic, use `SearchCriteria`:

```java
import static net.rainbowcreation.bonsai.query.SearchCriteria.where;

// Multiple AND conditions
List<User> results = users.find()
    .filter(
        where("status", QueryOp.EQ, "active")
            .and("age", QueryOp.GTE, 18)
            .and("age", QueryOp.LTE, 30)
    )
    .get();

// OR conditions
List<User> results = users.find()
    .filter(
        where("status", QueryOp.EQ, "premium")
            .or("status", QueryOp.EQ, "enterprise")
    )
    .get();

// Nested AND/OR: (status = "active") AND ((tier = "premium") OR (verified = true))
List<User> results = users.find()
    .filter(
        where("status", QueryOp.EQ, "active")
            .and(
                where("tier", QueryOp.EQ, "premium")
                    .or("verified", QueryOp.EQ, true)
            )
    )
    .get();

// You can also combine .where() and .filter() on the same query
List<User> results = users.find()
    .where("age", QueryOp.GT, 18)
    .filter(
        where("tier", QueryOp.EQ, "premium")
            .or("tier", QueryOp.EQ, "enterprise")
    )
    .get();
```

### Field Selection

Use `.select()` to fetch only specific fields, reducing bandwidth and deserialization overhead.
Requires `@BonsaiQuery` on the class or on the selected fields so they exist as SQL columns.

```java
// Fetch only name and email
List<User> results = users.find()
    .where("age", QueryOp.GT, 18)
    .select("name", "email")
    .get();

// Combine with sorting and pagination
List<User> results = users.find()
    .where("status", "active")
    .select("name", "age")
    .sort("age", SortOrder.DESC)
    .limit(50)
    .get();
```

### Sorting

```java
// Sort ascending by field
List<User> sorted = users.find()
    .where("status", QueryOp.EQ, "active")
    .sort("age", SortOrder.ASC)
    .get();

// Sort descending
List<User> sorted = users.find()
    .sort("name", SortOrder.DESC)
    .get();

// Default sort order is ASC
List<User> sorted = users.find()
    .sort("name")
    .get();
```

### Pagination

```java
// First page (20 results)
List<User> page1 = users.find()
    .where("status", QueryOp.EQ, "active")
    .sort("name", SortOrder.ASC)
    .limit(20)
    .get();

// Second page
List<User> page2 = users.find()
    .where("status", QueryOp.EQ, "active")
    .sort("name", SortOrder.ASC)
    .offset(20)
    .limit(20)
    .get();

// Get total count for pagination UI
Integer totalActive = users.find()
    .where("status", QueryOp.EQ, "active")
    .count();

int totalPages = (int) Math.ceil(totalActive / 20.0);
```

### Count

```java
// Count all matching records
Integer count = users.find()
    .where("status", QueryOp.EQ, "active")
    .count();

// Async count
BonsaiFuture<Integer> countFuture = users.find()
    .where("age", QueryOp.GTE, 18)
    .countAsync();

// Check if any records match
boolean hasResults = users.find()
    .where("email", QueryOp.EQ, "alice@example.com")
    .exists();
```

### Bulk Updates

```java
// Update a single field on all matching records
users.find()
    .where("status", QueryOp.EQ, "pending")
    .set("status", "active");

// Update multiple fields
users.find()
    .filter(
        where("tier", QueryOp.EQ, "trial")
            .and("created_at", QueryOp.LT, cutoffDate)
    )
    .set(Map.of(
        "tier", "expired",
        "status", "inactive"
    ));

// Async bulk update
BonsaiFuture<Void> future = users.find()
    .where("status", QueryOp.EQ, "pending")
    .setAsync("status", "active");
```

### Bulk Delete

```java
// Delete all matching records
users.find()
    .where("status", QueryOp.EQ, "banned")
    .delete();

// Async bulk delete
BonsaiFuture<Void> future = users.find()
    .filter(
        where("age", QueryOp.LT, 13)
            .and("verified", QueryOp.EQ, false)
    )
    .deleteAsync();
```

### Async Query Operations

Every query operation has an async counterpart:

```java
// Async get
BonsaiFuture<List<User>> future = users.find()
    .where("status", QueryOp.EQ, "active")
    .sort("name")
    .limit(50)
    .getAsync();

// Async count
BonsaiFuture<Integer> countFuture = users.find()
    .where("age", QueryOp.GTE, 18)
    .countAsync();

// Async exists
BonsaiFuture<Boolean> existsFuture = users.find()
    .where("email", QueryOp.EQ, "alice@example.com")
    .existsAsync();

// Async bulk update
BonsaiFuture<Void> updateFuture = users.find()
    .where("status", QueryOp.EQ, "pending")
    .setAsync("status", "active");

// Async bulk delete
BonsaiFuture<Void> deleteFuture = users.find()
    .where("status", QueryOp.EQ, "banned")
    .deleteAsync();
```

## Safe vs Unsafe Mode

Bonsai supports two write modes that trade off durability for latency.

### Safe Mode (Default)

Waits for the server to confirm the write is durably stored in the RocksDB WAL before
returning. The response includes a success/failure status.

```java
// Safe mode (default when no boolean argument is given)
BonsaiTable<User> users = db.use(User.class);
// Equivalent to:
BonsaiTable<User> users = db.use(User.class, true);

users.set("user:123", user);  // Blocks until WAL write confirmed
```

**Use when:**
- Data integrity is critical (orders, transactions, user accounts)
- You need confirmation of successful writes
- Building workflows that depend on write completion

**Performance:** ~33K writes/sec at high load (limited by server flush rate)

### Unsafe Mode

Fire-and-forget. The client sends the write and returns immediately without waiting for
server acknowledgment. The write is queued and will be processed asynchronously.

```java
// Unsafe mode
BonsaiTable<User> metrics = db.use(User.class, false);

metrics.set("metric:pageview", data);  // Returns immediately
```

**Use when:**
- Maximum throughput is needed
- Data loss on crash is acceptable (metrics, analytics, logs, caches)
- Network latency is high and you don't want to block

**Performance:** ~5x faster at low loads (1-100 requests). At high loads (10K+ requests),
converges with safe mode because the server becomes the bottleneck.

### Performance Comparison

| Load Level | Safe Mode Latency | Unsafe Mode Latency | Difference |
|------------|------------------|--------------------|-----------:|
| 1 request | ~150 us | ~30 us | 5.0x |
| 100 requests | ~80 us | ~25 us | 3.2x |
| 1,000 requests | ~45 us | ~30 us | 1.5x |
| 100,000 requests | ~30 us | ~30 us | 1.0x |

At scale, both modes reach approximately 33K writes/sec because the server-side
processing (RocksDB WAL write + response serialization) is the bottleneck, not the
client-side wait.

## Configuration

All client parameters are configurable via Java system properties (`-D` flags).

### Connection Settings

| Property | Default | Description |
|----------|---------|-------------|
| `bonsai.pool.size` | 4 | Number of TCP connections in the pool |
| `bonsai.pipeline.max` | 100 | Max concurrent requests per connection |
| `bonsai.write.flushThreshold` | 65536 | Write buffer size in bytes (64KB) |
| `bonsai.write.flushInterval` | 1 | Auto-flush interval in milliseconds |
| `bonsai.socket.sendBuffer` | 131072 | TCP SO_SNDBUF size (128KB) |
| `bonsai.socket.receiveBuffer` | 131072 | TCP SO_RCVBUF size (128KB) |

### Client-Side Cache

| Property | Default | Description |
|----------|---------|-------------|
| `bonsai.cache.enabled` | false | Enable local Caffeine cache |
| `bonsai.cache.maxSize` | 5000 | Maximum cached entries |
| `bonsai.cache.ttl` | 60 | Cache TTL in seconds |
| `bonsai.cache.stats` | false | Record cache hit/miss statistics |

**Note:** The client cache is disabled by default. When enabled, it caches GET results
locally. The cache does NOT receive server-side invalidation broadcasts, so entries may
become stale until TTL expires. Enable only when stale reads are acceptable.

### Profiling

| Property | Default | Description |
|----------|---------|-------------|
| `bonsai.profiler.enabled` | false | Enable client-side request tracing |
| `bonsai.profiler.output` | client-profile.log | Profiler log file path |
| `bonsai.profiler.sampleRate` | 1 | Sample 1 in N requests (1 = all) |

### Server Connection

```java
// Set host and port before first getBonsai() call
BonsApi.HOST = "192.168.1.10";    // Default: "127.0.0.1"
BonsApi.TCP_PORT = 4533;          // Default: 4533 (Edge server)
BonsApi.HTTP_PORT = 8080;         // Default: 8080 (HTTP fallback)
```

### Example Configurations

```bash
# Development (default, connects to localhost)
java -jar myapp.jar

# Production with client cache and more connections
java -Dbonsai.pool.size=8 \
     -Dbonsai.pipeline.max=200 \
     -Dbonsai.cache.enabled=true \
     -Dbonsai.cache.maxSize=50000 \
     -Dbonsai.cache.ttl=30 \
     -jar myapp.jar

# Low-latency configuration
java -Dbonsai.pool.size=16 \
     -Dbonsai.write.flushThreshold=4096 \
     -Dbonsai.write.flushInterval=0 \
     -jar myapp.jar

# Debug with profiling
java -Dbonsai.profiler.enabled=true \
     -Dbonsai.profiler.sampleRate=10 \
     -jar myapp.jar
```

## Best Practices

### 1. Reuse Bonsai and Table Instances

The `BonsApi.getBonsai()` singleton and `BonsaiRoot.use()` table instances are thread-safe
and designed to be long-lived. Create them once and reuse throughout your application.

```java
// GOOD: Static fields, created once
public class UserService {
    private static final Bonsai BONSAI = BonsApi.getBonsai();
    private static final BonsaiRoot DB = BONSAI.getRoot("myapp");
    private static final BonsaiTable<User> USERS = DB.use(User.class);

    public User getUser(String id) {
        return USERS.get(id);
    }
}

// BAD: Creating per request
public User getUser(String id) {
    Bonsai bonsai = BonsApi.getBonsai();              // Wasteful (but safe - singleton)
    BonsaiRoot db = bonsai.getRoot("myapp");          // Wasteful (schema re-registration)
    BonsaiTable<User> users = db.use(User.class);     // Wasteful (table re-creation)
    return users.get(id);
}
```

### 2. Use Async API for Bulk Operations

When performing multiple independent operations, use the async API to parallelize them:

```java
// GOOD: Parallel writes (all sent concurrently)
List<BonsaiFuture<Void>> futures = new ArrayList<>();
for (User user : usersToSave) {
    futures.add(table.setAsync(user.getId(), user));
}
// Wait for all to complete
for (BonsaiFuture<Void> f : futures) {
    f.get();
}

// GOOD: Using CompletableFuture composition
CompletableFuture<?>[] cfs = usersToSave.stream()
    .map(u -> table.setAsync(u.getId(), u).asCompletable())
    .toArray(CompletableFuture[]::new);
CompletableFuture.allOf(cfs).join();

// BAD: Sequential blocking writes
for (User user : usersToSave) {
    table.set(user.getId(), user);  // Each write blocks until confirmed
}
```

### 3. Choose Safe/Unsafe Mode Per Table

Different data has different durability requirements. Use safe mode for critical data
and unsafe mode for expendable data:

```java
// Critical data: safe mode
BonsaiTable<Order> orders = db.use(Order.class, true);
BonsaiTable<User> users = db.use(User.class, true);

// Expendable data: unsafe mode
BonsaiTable<Map> analytics = db.use("analytics", Map.class, false);
BonsaiTable<String> sessionCache = db.use("sessions", String.class, false);
```

### 4. Always Annotate Query Fields

Fields used in `.where()` clauses MUST have `@BonsaiQuery`. Without the annotation,
the field exists only in the serialized blob and cannot be filtered or sorted on.

```java
public class Product {
    @BonsaiQuery
    private String category;    // Queryable: creates indexed MySQL column

    @BonsaiQuery(unique = true)
    private String sku;          // Queryable with unique constraint

    private byte[] imageData;    // NOT queryable (stored in blob only)
}
```

Each `@BonsaiQuery` field creates a B-tree index in MySQL, which uses additional storage.
Only annotate fields you actually need to query on.

### 5. Handle Null Returns from GET

`get()` returns `null` when the key doesn't exist. Always check:

```java
User user = users.get("user:123");
if (user == null) {
    // Key not found - handle appropriately
    return Optional.empty();
}
return Optional.of(user);
```

### 6. Use Pagination for Large Queries

Query results are fully materialized in memory on the server. Large unbounded queries
can cause `OutOfMemoryError`:

```java
// GOOD: Paginated query
int pageSize = 100;
int offset = 0;
List<User> page;
do {
    page = users.find()
        .where("status", QueryOp.EQ, "active")
        .sort("name", SortOrder.ASC)
        .offset(offset)
        .limit(pageSize)
        .get();
    process(page);
    offset += pageSize;
} while (page.size() == pageSize);

// BAD: Unbounded query (may OOM on large tables)
List<User> allUsers = users.find()
    .where("status", QueryOp.EQ, "active")
    .get();  // Could return millions of rows!
```

### 7. Use count() Before Large Queries

Check how many results a query will return before fetching them:

```java
Integer count = users.find()
    .where("status", QueryOp.EQ, "active")
    .count();

if (count > 10_000) {
    // Use pagination instead
} else {
    List<User> results = users.find()
        .where("status", QueryOp.EQ, "active")
        .get();
}
```

### 8. Use select() for Bandwidth Efficiency

When you only need a few fields, use `.select()` to avoid fetching the full object:

```java
// GOOD: Fetch only needed fields
List<User> names = users.find()
    .where("status", "active")
    .select("name")
    .get();

// BAD: Fetching all fields when only name is needed
List<User> users = users.find()
    .where("status", "active")
    .get();
```

## Common Patterns

### Cache-Aside with Bonsai as Cache

Use Bonsai as a fast cache layer in front of your primary database:

```java
public class UserService {
    private final BonsaiTable<User> cache = db.use("user_cache", User.class, false);
    private final UserRepository repo;  // Your primary DB

    public User getUser(String id) {
        // Check Bonsai cache first
        User user = cache.get(id);
        if (user != null) return user;

        // Cache miss: load from primary DB
        user = repo.findById(id);
        if (user != null) {
            cache.set(id, user);  // Populate cache (unsafe = fast)
        }
        return user;
    }

    public void updateUser(User user) {
        repo.save(user);          // Write to primary DB
        cache.set(user.getId(), user);  // Update cache
    }
}
```

### Session Storage

```java
public class SessionStore {
    private final BonsaiTable<Map> sessions =
        db.use("sessions", Map.class, true);  // Safe: session data matters

    public void createSession(String sessionId, Map<String, Object> data) {
        data.put("created_at", System.currentTimeMillis());
        sessions.set(sessionId, data);
    }

    public Map<String, Object> getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void destroySession(String sessionId) {
        sessions.delete(sessionId);
    }
}
```

### Rate Limiter

```java
public class RateLimiter {
    private final BonsaiTable<Integer> counters =
        db.use("rate_limits", Integer.class, false);  // Unsafe: speed > accuracy

    public boolean isAllowed(String userId, int maxPerSecond) {
        String key = userId + ":" + (System.currentTimeMillis() / 1000);
        Integer count = counters.get(key);
        if (count != null && count >= maxPerSecond) {
            return false;
        }
        counters.set(key, (count == null ? 0 : count) + 1);
        return true;
    }
}
```

### Real-Time Leaderboard

```java
public class Leaderboard {
    private final BonsaiTable<Player> players = db.use(Player.class);

    // Player entity with queryable fields
    static class Player {
        @BonsaiQuery private String name;
        @BonsaiQuery private long score;
        @BonsaiQuery private String league;
        public Player() {}
    }

    public void updateScore(String playerId, long newScore) {
        Player p = players.get(playerId);
        p.setScore(newScore);
        players.set(playerId, p);
    }

    // Top 10 players in a league
    public List<Player> getTopPlayers(String league) {
        return players.find()
            .where("league", QueryOp.EQ, league)
            .sort("score", SortOrder.DESC)
            .limit(10)
            .get();
    }

    // Player count per league
    public Integer getLeagueSize(String league) {
        return players.find()
            .where("league", QueryOp.EQ, league)
            .count();
    }
}
```

### Feature Flags

```java
public class FeatureFlags {
    private final BonsaiTable<Boolean> flags =
        db.use("features", Boolean.class, true);

    public boolean isEnabled(String featureName) {
        Boolean enabled = flags.get(featureName);
        return enabled != null && enabled;
    }

    public void setFlag(String featureName, boolean enabled) {
        flags.set(featureName, enabled);
    }
}
```

## Troubleshooting

### Connection Errors

**`RuntimeException: Fatal: No connection method found`**

The client could not connect via any method (ServiceLoader, TCP, or HTTP).

1. Verify the Bonsai server is running:
   ```bash
   # Check if Edge server is listening
   nc -z localhost 4533 && echo "OK" || echo "FAIL"
   ```
2. Check host and port configuration (if needed):
   ```java
   BonsApi.HOST = "127.0.0.1";  // Must be set BEFORE getBonsai()
   BonsApi.TCP_PORT = 4533;
   ```
3. Check firewall rules allow TCP connections to the server port(if connect to non-local Bonsai server).

**`TimeoutException: Pipeline acquire timeout`**

All pipeline slots are occupied (100 per connection x 4 connections = 400 max in-flight).

1. Increase pool size: `-Dbonsai.pool.size=8`
2. Increase pipeline depth: `-Dbonsai.pipeline.max=200`
3. Check if the server is under heavy load or unresponsive.

### Performance Issues

**Slow writes**

1. Switch to unsafe mode for non-critical data: `db.use(MyType.class, false)`
2. Use async API: `setAsync()` instead of `set()`
3. Increase connections: `-Dbonsai.pool.size=8`
4. The server's single MySQL flusher thread limits write throughput to ~33K ops/sec.

**Slow reads**

1. Enable client cache: `-Dbonsai.cache.enabled=true -Dbonsai.cache.maxSize=50000`
2. Increase server cache: `-Dbonsai.cache.maxSize=5000000` (on server)
3. Reads should be ~138K ops/sec when cached. If slower, check network latency.

**Slow queries**

1. Ensure `@BonsaiQuery` annotations exist on filtered fields.
2. Queries require `flushNow()` on the server, which can take 50-500ms if the WAL has
   unflushed data. This is expected behavior for consistency.
3. Use `limit()` to reduce result set size.
4. Use `select()` to fetch only needed fields.
5. Consider increasing server batch size: `-Dbonsai.rocksdb.flushBatchSize=50000`

### Data Issues

**Stale reads after writes**

This can happen due to the cache invalidation race window (~1ms):
1. Use safe mode to ensure the write is confirmed before reading.
2. Reduce server cache TTL: `-Dbonsai.cache.expireSeconds=5`
3. For guaranteed freshness, read directly from the Master server instead of Edge.

**`ClassCastException` on GET**

The stored type doesn't match the expected type. This happens when:
1. The table type was changed between writes (e.g., stored String, reading as Integer).
2. Multiple applications write different types to the same table.
3. Solution: Use consistent types per table across all clients.

**`null` returned for a key that was just written**

In unsafe mode, the write may not have reached the server yet when you read.
Switch to safe mode or add a small delay after writing.

## Architecture Overview

```
Your Application
    |
    v
BonsApi.getBonsai() --> Bonsai (singleton)
    |
    v
bonsai.getRoot("db") --> BonsaiRoot (per database)
    |                      +-- Schema registration (REGISTER_SCHEMA)
    |                      +-- IdRegistry (name <-> compact ID mapping)
    v
db.use(User.class) --> BonsaiTable<User> (per table)
    |                      +-- Local Caffeine cache (optional)
    |                      +-- Primitive fast-path encoder
    |                      +-- Fory serializer (POJOs)
    v
table.set/get/delete --> ConnectionPool
    |                      +-- TcpConnection[0] -+
    |                      +-- TcpConnection[1] -+
    |                      +-- TcpConnection[2] -+--> Edge Server (port 4533)
    |                      +-- TcpConnection[3] -+        |
    |                                                     v
    |                                              Master Server (port 4532)
    |                                                     |
    |                                              +------+------+
    |                                              v             v
    |                                          RocksDB WAL     MySQL
    |                                         (fast write)   (durable)
    v
BonsaiFuture<T> <-- Response from server
```

---

**Client Version:** 0.1.0
**Server Compatibility:** 0.1.0+
**Java Version:** 8+
