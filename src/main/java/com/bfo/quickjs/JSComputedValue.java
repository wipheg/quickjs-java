package com.bfo.quickjs;

/**
 * A JSComputableValue is dynamically computed value which can
 * be set on a JSOBject.
 */
public interface JSComputedValue {

    /**
     * Return the value for the specified property on the specified owner
     * @param owner the owner object (currently, will always be a JSObject)
     * @param key the key
     * @return the object value
     */
    public Object get(JSType owner, Object key);

    /**
     * Set the value for the specified property on the specified owner
     * @param owner the owner object (currently, will always be a JSObject)
     * @param key the key
     * @param value the key
     * @return whether the value was set. Default implementation returns false
     */
    public default boolean set(JSType owner, Object key, Object value) {
         return false;
    }

    /**
     * Delete the value for the specified property on the specified owner
     * @param owner the owner object (currently, will always be a JSObject)
     * @param key the key
     * @return whether the value was deleted. Default implementation returns false
     */
    public default boolean delete(JSType owner, Object key) {
         return false;
    }

    /**
     * Return true if this property should be enumerated in the object key set
     * @return true if the property is enumerable (default to true)
     */
    public default boolean isEnumerable() {
        return true;
    }

    /**
     * Return true if this property can be updated by calling {@link #set}
     * @return true if the property is writable (default to false)
     */
    public default boolean isWritable() {
        return false;
    }

    /**
     * Return true if this property can be deleted from its object
     * @return true if the property is deletable (default to false)
     */
    public default boolean isDeletable() {
        return false;
    }
}
