//package com.foggy.navigator.launcher;
//
//import org.springframework.context.annotation.Configuration;
//import org.springframework.web.servlet.config.annotation.CorsRegistry;
//import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
//
//@Configuration
//public class CorsConfig implements WebMvcConfigurer {
//
//    @Override
//    public void addCorsMappings(CorsRegistry registry) {
//        registry.addMapping("/**")
//                .allowedOrigins("*")  // 允许所有来源
//                .allowedMethods("*")  // 允许所有HTTP方法
//                .allowedHeaders("*")  // 允许所有请求头
//                .allowCredentials(false) // 不允许凭证
//                .maxAge(3600);  // 预检请求的缓存时间
//    }
//}
