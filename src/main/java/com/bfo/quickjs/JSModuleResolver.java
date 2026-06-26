package com.bfo.quickjs;

/** 
 * An interface which can be implemented to load modules
 */
public interface JSModuleResolver {

    /**
     * Given a base path and a module path, return the normalized module path
     * @param path the non normalized path from the import statement
     * @param base the normalized path of the calling module
     * @return the normalized module path
     */
    String normalize(String path, String base);

    /**
     * Load a module
     * @param module the normalized path of the module
     * @return the script
     * @throws a RuntimeException if something went wrong
     */
     String load(String module);

}
