package com.lianyu.qqbridge.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * QQ 桥内部组件的复合注解：既是 Spring {@code @Component}，又被
 * {@code lianyu.qq-bridge.enabled=true} 门控。enabled 缺省或为 false 时，
 * 所有标注本注解的 Bean 都不会创建，桥完全静默，对主应用零影响。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@ConditionalOnProperty(prefix = "lianyu.qq-bridge", name = "enabled", havingValue = "true")
public @interface QqBridgeComponent {
}
