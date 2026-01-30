# BonsApi

A high-performance distributed key-value database client for Java applications. BonsApi provides a simple, intuitive API to store and retrieve data with automatic caching, serialization, connection pooling, and query support.

## Features

- Simple key-value operations (get, set, delete, exists)
- Automatic object serialization (POJOs, Lists, Maps, nested objects)
- Query support with filtering, sorting, and pagination
- Async-first API with `BonsaiFuture` for non-blocking operations
- Connection pooling with round-robin + least-pending load balancing
- Request pipelining (up to 100 concurrent requests per connection)
- Write coalescing for efficient network utilization
- Multi-Release JAR supporting Java 8, 11, 17, 21, and 25
- Automatic connection management (TCP with HTTP fallback)

## Installation

### Maven

```xml
<repository>
    <id>rainbowcreation</id>
    <url>https://repo.rainbowcreation.net</url>
</repository>

<dependency>
    <groupId>net.rainbowcreation.bonsai</groupId>
    <artifactId>BonsApi</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Gradle

```groovy
repositories {
    maven { url 'https://repo.rainbowcreation.net' }
}

dependencies {
    implementation 'net.rainbowcreation.bonsai:BonsApi:0.1.0-SNAPSHOT'
}
```

## Quick Start

### 1. Define Your Data Class

```java
public class Player {
    private String name;
    private int level;
    private long lastLogin;

    // Constructors, getters, setters...
}
```

### 2. Connect and Use

```java
import net.rainbowcreation.bonsai.api.*;

// Get the Bonsai instance (auto-connects to localhost:4533)
Bonsai bonsai = BonsApi.getBonsai();

// Access a database and table
BonsaiRoot db = bonsai.getRoot("myapp");
BonsaiTable<Player> players = db.use(Player.class);

// Store data (fire-and-forget, async write-behind)
players.set("player123", new Player("Steve", 42, System.currentTimeMillis()));

// Retrieve data
Player player = players.get("player123");

// Check existence
boolean exists = players.exists("player123");

// Delete data
players.delete("player123");
```

### 3. Async Operations

```java
// Non-blocking operations
BonsaiFuture<Player> future = players.getAsync("player123");

// Chain operations
future.map(p -> p.getLevel())
      .thenAccept(level -> System.out.println("Level: " + level));

// Wait when needed
Player player = future.get();

// Fire-and-forget writes (default behavior - fastest)
players.set("player123", player);

// Wait for server acknowledgment
players.setAsync("player123", player).get();
```

## Query API

Enable queries on specific fields using the `@BonsaiQuery` annotation:

```java
import net.rainbowcreation.bonsai.api.annotation.BonsaiQuery;

// Optional: Add @BonsaiQuery at class level to enable query on all fields
public class Player {
    private String name;

    @BonsaiQuery  // Enable queries on this field
    private int level;

    @BonsaiQuery
    private String region;

    @BonsaiIgnore  // Exclude from queries if @BonsaiQuery is on class
    private String secret;

    // ...
}
```

Then query your data:

```java
import net.rainbowcreation.bonsai.api.query.*;

// Find players with level > 50
List<Player> highLevel = players.find()
    .where("level", QueryOp.GT, 50)
    .get();

// Complex queries
List<Player> results = players.find()
    .where("level", QueryOp.GTE, 10)
    .where("region", QueryOp.EQ, "EU")
    .sort("level", SortOrder.DESC)
    .limit(100)
    .get();

// Count matching records
long count = players.find()
    .where("level", QueryOp.GT, 50)
    .count();

// Bulk updates
players.find()
    .where("region", QueryOp.EQ, "US")
    .setAsync("status", "active")
    .get();

// Bulk deletes
players.find()
    .where("lastLogin", QueryOp.LT, cutoffTime)
    .deleteAsync()
    .get();
```

### Query Operators

| Operator | Description |
|----------|-------------|
| `EQ` | Equal |
| `NE` | Not equal |
| `GT` | Greater than |
| `GTE` | Greater than or equal |
| `LT` | Less than |
| `LTE` | Less than or equal |
| `IN` | In list |
| `NIN` | Not in list |
| `LIKE` | Pattern match (SQL LIKE) |

## Configuration

Configure the connection before first use:

```java
// Set connection parameters (before calling getBonsai())
BonsApi.HOST = "192.168.1.100";  // Default: 127.0.0.1
BonsApi.TCP_PORT = 4533;         // Default: 4533
BonsApi.HTTP_PORT = 8080;        // Default: 8080 (fallback)

// Then connect
Bonsai bonsai = BonsApi.getBonsai();
```

### System Properties

Fine-tune performance via system properties:

```bash
java -Dbonsai.pool.size=8 \
     -Dbonsai.pipeline.max=200 \
     -Dbonsai.write.flushThreshold=131072 \
     -jar myapp.jar
```

| Property | Default | Description |
|----------|---------|-------------|
| `bonsai.pool.size` | 4 | Number of connections in the pool |
| `bonsai.pipeline.max` | 100 | Max pending requests per connection |
| `bonsai.write.flushThreshold` | 65536 | Write buffer flush threshold (bytes) |
| `bonsai.write.flushInterval` | 1 | Auto-flush interval (ms) |
| `bonsai.socket.sendBuffer` | 131072 | Socket send buffer size |
| `bonsai.socket.receiveBuffer` | 131072 | Socket receive buffer size |

## Shutdown

```java
// Graceful shutdown (flushes pending writes)
BonsApi.shutdown();
```

## Supported Data Types

BonsApi automatically serializes:

- Primitives (int, long, double, boolean, etc.)
- Strings
- Enums
- Collections (List, Set, Map)
- Nested objects
- Arrays

## Architecture

```
Your Application
       ↓
   BonsApi (ConnectionPool)
       ↓ TCP (pipelined requests)
   Edge Server (Caffeine cache)
       ↓
   Relay Server (optional proxy)
       ↓
   Master Server (MySQL + WAL)
```

## Requirements

- Java 8 or higher
- Bonsai server running (Edge, Relay, or Master)

## Performance Tips

1. **Use async operations** for throughput - `setAsync()` returns immediately
2. **Batch related operations** - they'll be coalesced in the write buffer
3. **Increase pool size** for high-concurrency workloads (`-Dbonsai.pool.size=8`)
4. **Add query indices** only on fields you actually query (`@BonsaiQuery`)

## Status

This project is under active development. API is stable for 0.1.x releases.
