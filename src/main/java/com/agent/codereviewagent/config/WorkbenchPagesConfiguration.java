package com.agent.codereviewagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 只为已有页面提供 history 刷新入口；API、webhook 和缺失资源仍由各自处理器响应。 */
@Configuration(proxyBeanMethods = false)
public class WorkbenchPagesConfiguration implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : new String[]{"/repositories", "/repositories/", "/reviews", "/reviews/"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
