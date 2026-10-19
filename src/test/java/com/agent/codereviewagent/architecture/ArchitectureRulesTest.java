package com.agent.codereviewagent.architecture;

import com.agent.codereviewagent.common.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.MediaType;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** 把 Controller 与 Service 的机械分层约定变成可重复执行的回归检查。 */
class ArchitectureRulesTest {

    private static final String BASE_PACKAGE = "com.agent.codereviewagent";
    private static final String DTO_PACKAGE = BASE_PACKAGE + ".models.dto";

    @Test
    void nonStreamingControllerEndpointsReturnTypedResult() {
        List<String> violations = new ArrayList<>();

        for (Class<?> controller : controllerTypes()) {
            for (Method method : endpointMethods(controller)) {
                RequestMapping mapping = endpointMapping(method);
                if (isStreaming(mapping)) {
                    continue;
                }
                if (!Result.class.equals(method.getReturnType())
                        || !(method.getGenericReturnType() instanceof ParameterizedType)) {
                    violations.add(method.toGenericString());
                }
            }
        }

        assertThat(violations)
                .as("非流式 Controller 端点必须返回 Result<?>；流式端点用 text/event-stream 显式豁免")
                .isEmpty();
    }

    @Test
    void controllerRequestBodiesUseDtoTypes() {
        List<String> violations = new ArrayList<>();

        for (Class<?> controller : controllerTypes()) {
            for (Method method : endpointMethods(controller)) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)
                            && !DTO_PACKAGE.equals(parameter.getType().getPackageName())) {
                        violations.add(method.toGenericString() + " -> " + parameter.getType().getName());
                    }
                }
            }
        }

        assertThat(violations)
                .as("Controller 的 @RequestBody 类型必须来自 models.dto")
                .isEmpty();
    }

    @Test
    void controllersKeepCommonPrefixAtClassLevelAndEndpointAtMethodLevel() {
        List<String> violations = new ArrayList<>();

        for (Class<?> controller : controllerTypes()) {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            if (!hasExplicitPath(classMapping)) {
                violations.add(controller.getName() + " 缺少类级公共路由");
            }
            for (Method method : endpointMethods(controller)) {
                if (!hasExplicitPath(endpointMapping(method))) {
                    violations.add(method.toGenericString() + " 缺少方法级端点路由");
                }
            }
        }

        assertThat(violations)
                .as("Controller 类声明公共前缀，端点方法声明非空子路径")
                .isEmpty();
    }

    @Test
    void serviceLayerDoesNotDependOnJacksonTreeModel() {
        JavaClasses classes = new ClassFileImporter().importPackages(BASE_PACKAGE);

        noClasses()
                .that().resideInAnyPackage("..service..")
                .should().dependOnClassesThat().areAssignableTo(JsonNode.class)
                .because("Service 应接收类型化 DTO 或领域对象，而不是 Jackson tree model")
                .check(classes);
    }

    private static Set<Class<?>> controllerTypes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(ArchitectureRulesTest::loadClass)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Class<?> loadClass(String className) {
        try {
            return ClassUtils.forName(className, ArchitectureRulesTest.class.getClassLoader());
        }
        catch (ClassNotFoundException exception) {
            throw new IllegalStateException("无法加载 Controller: " + className, exception);
        }
    }

    private static List<Method> endpointMethods(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> endpointMapping(method) != null)
                .toList();
    }

    private static RequestMapping endpointMapping(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    }

    private static boolean isStreaming(RequestMapping mapping) {
        return Arrays.asList(mapping.produces()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private static boolean hasExplicitPath(RequestMapping mapping) {
        if (mapping == null) {
            return false;
        }
        return Stream.concat(Arrays.stream(mapping.value()), Arrays.stream(mapping.path()))
                .anyMatch(path -> !path.isBlank());
    }
}
