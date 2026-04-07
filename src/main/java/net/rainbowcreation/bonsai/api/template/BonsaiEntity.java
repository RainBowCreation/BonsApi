package net.rainbowcreation.bonsai.api.template;

import net.rainbowcreation.bonsai.BonsaiTable;
import net.rainbowcreation.bonsai.WriteMode;
import net.rainbowcreation.bonsai.annotation.BonsaiConsistent;
import net.rainbowcreation.bonsai.annotation.BonsaiSafe;
import net.rainbowcreation.bonsai.annotation.BonsaiTtl;
import net.rainbowcreation.bonsai.annotation.BonsaiUnsafe;
import net.rainbowcreation.bonsai.annotation.BonsaiVolatile;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public abstract class BonsaiEntity<T extends BonsaiEntity<T>> {
    protected transient BonsaiTable<T> _table;
    protected transient String _key;

    private static final ConcurrentHashMap<Class<?>, WriteMode> MODE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, long[]> TTL_CACHE = new ConcurrentHashMap<>();
    private static final long[] NO_TTL = null;

    @SuppressWarnings("unchecked")
    public T attach(BonsaiTable<T> table, String key) {
        this._table = table;
        this._key = key;
        return (T) this;
    }

    /**
     * Saves this entity using the write mode and TTL determined by class annotations.
     *
     * <p>Write mode priority:
     * <ul>
     *   <li>{@link BonsaiVolatile @BonsaiVolatile} → no WAL, no MySQL, edge-cached only (fastest)</li>
     *   <li>{@link BonsaiUnsafe @BonsaiUnsafe} → async WAL, fire-and-forget</li>
     *   <li>{@link BonsaiSafe @BonsaiSafe} or no annotation → wait for WAL durability (default)</li>
     *   <li>{@link BonsaiConsistent @BonsaiConsistent} → wait for WAL + all edge ACKs (strongest)</li>
     * </ul>
     *
     * <p>If {@link BonsaiTtl @BonsaiTtl} is present, the entry auto-expires after the
     * specified duration.
     */
    @SuppressWarnings("unchecked")
    public void save() {
        requireAttached("save");
        long[] ttl = resolveTtl();
        if (ttl.length > 0) {
            // TTL path — uses table's default writeMode (set via annotation on root.use())
            _table.set(_key, (T) this, ttl[0], TimeUnit.MILLISECONDS);
        } else {
            WriteMode mode = resolveWriteMode();
            if (mode == WriteMode.UNSAFE) {
                _table.setAsync(_key, (T) this, mode);
            } else {
                _table.set(_key, (T) this, mode);
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

    private WriteMode resolveWriteMode() {
        return MODE_CACHE.computeIfAbsent(getClass(), cls -> {
            if (cls.isAnnotationPresent(BonsaiConsistent.class)) return WriteMode.CONSISTENT;
            if (cls.isAnnotationPresent(BonsaiUnsafe.class))     return WriteMode.UNSAFE;
            return WriteMode.SAFE;
        });
    }

    private long[] resolveTtl() {
        return TTL_CACHE.computeIfAbsent(getClass(), cls -> {
            BonsaiTtl ann = cls.getAnnotation(BonsaiTtl.class);
            if (ann == null) return new long[0]; // sentinel for "no TTL"
            return new long[]{ ann.unit().toMillis(ann.value()) };
        });
    }
}
