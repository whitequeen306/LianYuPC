package com.lianyu.common.util;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class SpringContextHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        context = applicationContext;
    }

    public static <T> T getBean(Class<T> clazz) {
        if (context == null) {
            throw new IllegalStateException("ApplicationContext not initialized");
        }
        return context.getBean(clazz);
    }

    public static Object getBean(String name) {
        if (context == null) {
            throw new IllegalStateException("ApplicationContext not initialized");
        }
        return context.getBean(name);
    }
}
