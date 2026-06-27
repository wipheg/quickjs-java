package com.bfo.quickjs;

import java.lang.reflect.*;
import org.junit.jupiter.api.Test;

/**
 * Run tests without Maven
 *
 * Usage: java TestHarness className[.methodName] ...
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class TestHarness {

    public static void main(String[] args) throws Exception {

        for (int i=0;i<args.length;i++) {

            JSLogger logger = JSLogger.toSystem(JSRuntime.class.getPackage().getName());
            String className = args[i], methodName = null;
            if (className.indexOf(".") > 0) {
                methodName = className.substring(className.indexOf(".") + 1);
                className = className.substring(0, className.indexOf("."));
            }
            Class c = Class.forName(TestHarness.class.getPackage().getName() + "." + className);
            Object o = null;
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class) && (methodName == null || methodName.equals(m.getName()))) {
                    if (o == null) {
                        o = c.getDeclaredConstructor().newInstance();
                    }
                    logger.log(logger.INFO, "Test " + m.getName());
                    m.invoke(o);
                }
            }
        }
    }
}
