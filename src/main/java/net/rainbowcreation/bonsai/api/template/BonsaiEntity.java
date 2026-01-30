package net.rainbowcreation.bonsai.api.template;

import net.rainbowcreation.bonsai.api.BonsaiTable;

public abstract class BonsaiEntity<T extends BonsaiEntity<T>> {
    protected transient BonsaiTable<T> _table;
    protected transient String _key;

    /**
     * Attaches the table and key to this entity so it can execute operations.
     * @param table The source table
     * @param key The key this entity was loaded from
     * @return this (for chaining)
     */
    @SuppressWarnings("unchecked")
    public T attach(BonsaiTable<T> table, String key) {
        this._table = table;
        this._key = key;
        return (T) this;
    }

    /**
     * Save this entity back to the database using the attached key.
     */
    @SuppressWarnings("unchecked")
    public void save() {
        if (_table == null || _key == null) {
            throw new IllegalStateException("Cannot save: Entity not attached to a BonsaiTable. Call attach() first.");
        }
        _table.setAsync(_key, (T) this);
    }

    /**
     * Delete this entity from the database.
     */
    public void delete() {
        if (_table == null || _key == null) {
            throw new IllegalStateException("Cannot delete: Entity not attached to a BonsaiTable.");
        }
        _table.delete(_key);
    }

    /**
     * Helper to load and immediately attach.
     * Replaces: table.get("key")
     * Usage: Hero h = BonsaiEntity.fetch(heroTable, "nathaniel");
     */
    public static <E extends BonsaiEntity<E>> E fetch(BonsaiTable<E> table, String key) {
        E entity = table.get(key);
        if (entity != null) {
            entity.attach(table, key);
        }
        return entity;
    }
}