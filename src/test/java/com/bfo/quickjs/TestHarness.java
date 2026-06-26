package com.bfo.quickjs;

import java.lang.reflect.*;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"rawtypes", "unchecked"})
public class TestHarness {
    public static void main(String[] args) throws Exception {
        for (int i=0;i<args.length;i++) {
            JSLogger logger = JSLogger.toSystem(JSRuntime.class.getPackage().getName());
            String s = args[i];
            Class c = Class.forName(TestHarness.class.getPackage().getName() + "." + s);
            Object o = null;
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class)) {
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
