package com.github.feng111627.metadatalineagecatalogjava.tests.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注测试用例所属的业务模块与用例名称，用于测试日志分组展示。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BusinessCase {

    /** 业务模块名称。 */
    String value();

    /** 用例名称；方法上缺省时取方法名。 */
    String caseName() default "";
}
