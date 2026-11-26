package com.zhiye.app;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 托管构建后的 Vue 前端静态产物（优先使用运行态 STATIC_DIR，否则回退 JAR 内 classpath:/static/）并支持 SPA 路由回退。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String staticDir = System.getenv("STATIC_DIR");
        String location;
        if (staticDir != null && !staticDir.isBlank() && Files.isDirectory(Path.of(staticDir))) {
            String uri = Path.of(staticDir).toUri().toString();
            location = uri.endsWith("/") ? uri : uri + "/";
        } else {
            location = "classpath:/static/";
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource loc) throws IOException {
                        if (resourcePath.startsWith("api/")) return null;
                        if (!resourcePath.isEmpty()) {
                            Resource requested = loc.createRelative(resourcePath);
                            if (requested.exists() && requested.isReadable() && !isDirectory(requested)) {
                                return requested;
                            }
                        }
                        Resource index = loc.createRelative("index.html");
                        if (index.exists() && index.isReadable()) return index;
                        if (staticDir != null && !staticDir.isBlank()) {
                            Path fallback = Path.of(staticDir, "index.html");
                            if (Files.isRegularFile(fallback)) return new FileSystemResource(fallback);
                        }
                        return null;
                    }

                    private boolean isDirectory(Resource resource) {
                        try {
                            return resource.isFile() && resource.getFile().isDirectory();
                        } catch (IOException e) {
                            return false;
                        }
                    }
                });
    }
}
