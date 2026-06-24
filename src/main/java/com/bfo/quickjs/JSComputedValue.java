package com.bfo.quickjs;

/**
 * A JSComputableValue is dynamically computed value which can
 * be set on a JSOBject.
 */
public interface JSComputedValue {

    /**
     * Return the value for the specified property on the specified owner
     * @param owner the owner object
     * @param key the key
     * @return the object value
     */
    public Object get(JSObject owner, String key);

    /**
     * Set the value for the specified property on the specified owner.
     * If this default implementation is not overridden, the property is read-only.
     * @param owner the owner object
     * @param key the key
     * @param value the key
     * @throws RuntimeException if the value is invalid
     */
    public default void set(JSObject owner, String key, Object value) {
    }

    /**
     * Return true if this property should be hidden in the object key set
     * @return true if the property is hidden (default to false)
     */
    public default boolean isHidden() {
        return false;
    }

    /**
     * Return true if this property may be deleted from the object.
     * @return true if the property is deletable (default to false)
     */
    public default boolean isDeleteable() {
        return false;
    }

}
