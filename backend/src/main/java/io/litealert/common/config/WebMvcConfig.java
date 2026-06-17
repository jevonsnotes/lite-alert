package io.litealert.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA support: any non-API route falls back to {@code index.html} so that Vue
 * Router can take over client-side routing.
 *
 * <p>API and Webhook endpoints live under {@code /api/**}; everything else is
 * served from {@code classpath:/static/}.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // SPA fallback: forward paths that end with a non-dot segment to
        // index.html so Vue Router can resolve them.
        //
        // The {spring:...} named capture avoids colliding with Spring's
        // default path-parameter namespace and prevents "/api/**" requests
        // from being forwarded (they are already claimed by @RestController
        // methods with higher priority).
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/**/{spring:[^.]*}")
                .setViewName("forward:/index.html");
    }
}
