package net.rainbowcreation.bonsai.api.template;

import net.rainbowcreation.bonsai.BonsaiTable;
import net.rainbowcreation.bonsai.WriteMode;
import net.rainbowcreation.bonsai.annotation.BonsaiConsistent;
import net.rainbowcreation.bonsai.annotation.BonsaiSafe;
import net.rainbowcreation.bonsai.annotation.BonsaiTtl;
import net.rainbowcreation.bonsai.annotation.BonsaiUnsafe;
import net.rainbowcreation.bonsai.annotation.BonsaiVolatile;
import net.rainbowcreation.bonsai.annotation.EntityMetadata;

import java.util.concurrent.TimeUnit;

public abstract class BonsaiEntity<T extends BonsaiEntity<T>> {
    protected transient BonsaiTable<T> _table;
    protected transient String _key;

    @SuppressWarnings("unchecked")
    public T attach(BonsaiTable<T> table, String key) {
        this._table = table;
        this._key = key;
        return (T) this;
    }

    /**
     * Saves this entity using the write mode and TTL determined by annotations on the
     * class and/or its fields. Field-level annotations override class-level. Across
     * multiple fields, strictest write mode wins and shortest TTL wins.
     *
     * <p>Write mode priority:
     * <ul>
     *   <li>{@link BonsaiVolatile @BonsaiVolatile} (alone) → local cache only, never hits network</li>
     *   <li>{@link BonsaiUnsafe @BonsaiUnsafe} → async WAL, fire-and-forget</li>
     *   <li>{@link BonsaiSafe @BonsaiSafe} or no annotation → wait for WAL durability (default)</li>
     *   <li>{@link BonsaiConsistent @BonsaiConsistent} → wait for WAL + all edge ACKs (strongest)</li>
     * </ul>
     *
     * <p>All annotation metadata is baked into a single {@link EntityMetadata} object
     * cached via {@link ClassValue} — one lookup per save, no reflection on the hot path.
     */
    @SuppressWarnings("unchecked")
    public void save() {
        requireAttached("save");
        EntityMetadata meta = EntityMetadata.of(getClass());

        // Volatile-only (no explicit write mode) → local cache, fire-and-forget
        if (meta.localOnly) {
            _table.setAsync(_key, (T) this);
            return;
        }
        if (meta.ttlMs >= 0) {
            // TTL path — uses table's default writeMode
            _table.set(_key, (T) this, meta.ttlMs, TimeUnit.MILLISECONDS);
        } else {
            if (meta.writeMode == WriteMode.UNSAFE) {
                _table.setAsync(_key, (T) this, meta.writeMode);
            } else {
                _table.set(_key, (T) this, meta.writeMode);
            }
        }
    }

    /** Saves with explicit unsafe mode, ignoring class annotation. */
    @SuppressWarnings("unchecked")
    public void saveUnsafe() {
        requireAttached("saveUnsafe");
        _table.setAsync(_key, (T) this, WriteMode.UNSAFE);
    }

    /** Saves with explicit safe mode, ignoring class annotation. */
    @SuppressWarnings("unchecked")
    public void saveSafe() {
        requireAttached("saveSafe");
        _table.set(_key, (T) this, WriteMode.SAFE);
    }

    /** Saves with explicit consistent mode, ignoring class annotation. Blocks until all edges ACK. */
    @SuppressWarnings("unchecked")
    public void saveConsistent() {
        requireAttached("saveConsistent");
        _table.set(_key, (T) this, WriteMode.CONSISTENT);
    }

    public void delete() {
        requireAttached("delete");
        _table.delete(_key);
    }

    public static <E extends BonsaiEntity<E>> E fetch(BonsaiTable<E> table, String key) {
        E entity = table.get(key);
        if (entity != null) {
            entity.attach(table, key);
        }
        return entity;
    }

    private void requireAttached(String method) {
        if (_table == null || _key == null) {
            throw new IllegalStateException("Cannot " + method + ": Entity not attached to a BonsaiTable. Call attach() first.");
        }
    }
}
