package com.example.sai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ============================================================
 * Step 6 · 跨域(CORS)配置
 * ------------------------------------------------------------
 * React 前端开发服务器跑在 http://localhost:5173，跟后端(8082)不同源，
 * 浏览器默认会拦掉跨域请求。这里放行本地前端端口。
 * 生产环境应收紧 allowedOrigins 到真实域名。
 * ============================================================
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
