package com.aioj.next.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup-wiring guard (P3-8 lesson, 2026-08-08): Spring falls back to a
 * no-arg constructor when a bean class declares multiple constructors and
 * none is annotated with {@code @Autowired} — failing at runtime startup with
 * "No default constructor found", which unit tests that instantiate classes
 * directly never catch. This test statically scans every Spring stereotype
 * under {@code com.aioj.next.ai} and fails the build instead.
 */
class SpringBeanConstructorConventionTest {

    @Test
    void componentsWithMultipleConstructorsMustDeclareAnAutowiredConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> violations = new ArrayList<>();
        for (org.springframework.beans.factory.config.BeanDefinition bean
                : scanner.findCandidateComponents("com.aioj.next.ai")) {
            Class<?> type = Class.forName(bean.getBeanClassName(), false,
                    SpringBeanConstructorConventionTest.class.getClassLoader());
            if (type.isInterface() || type.isAnnotation() || type.isEnum()
                    || Modifier.isAbstract(type.getModifiers())) {
                continue;
            }
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) {
                continue;
            }
            boolean hasAutowiredConstructor = false;
            boolean hasNoArgConstructor = false;
            for (Constructor<?> constructor : constructors) {
                if (constructor.isAnnotationPresent(Autowired.class)) {
                    hasAutowiredConstructor = true;
                }
                if (constructor.getParameterCount() == 0) {
                    hasNoArgConstructor = true;
                }
            }
            if (!hasAutowiredConstructor && !hasNoArgConstructor) {
                violations.add(type.getName() + " declares " + constructors.length
                        + " constructors but none is @Autowired and no no-arg constructor exists; "
                        + "Spring startup would fail with 'No default constructor found'.");
            }
        }
        assertThat(violations)
                .as("Spring bean constructor wiring violations (annotate the injection constructor "
                        + "with @Autowired; keep test-only convenience constructors package-private)")
                .isEmpty();
    }
}
