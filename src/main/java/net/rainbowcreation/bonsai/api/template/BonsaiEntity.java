package net.rainbowcreation.bonsai.api.template;

import net.rainbowcreation.bonsai.BonsaiTable;

public abstract class BonsaiEntity<T extends BonsaiEntity<T>> {
    protected transient BonsaiTable<T> _table;
    protected transient String _key;

    @SuppressWarnings("unchecked")
    public T attach(BonsaiTable<T> table, String key) {
        this._table = table;
        this._key = key;
        return (T) this;
    }

    @SuppressWarnings("unchecked")
    public void save() {
        if (_table == null || _key == null) {
            throw new IllegalStateException("Cannot save: Entity not attached to a BonsaiTable. Call attach() first.");
        }
        _table.setAsync(_key, (T) this);
    }

    public void delete() {
        if (_table == null || _key == null) {
            throw new IllegalStateException("Cannot delete: Entity not attached to a BonsaiTable.");
        }
        _table.delete(_key);
    }

    public static <E extends BonsaiEntity<E>> E fetch(BonsaiTable<E> table, String key) {
        E entity = table.get(key);
        if (entity != null) {
            entity.attach(table, key);
        }
        return entity;
    }
}