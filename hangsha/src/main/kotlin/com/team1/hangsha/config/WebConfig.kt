package com.team1.hangsha.config

import com.team1.hangsha.user.UserArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val userArgumentResolver: UserArgumentResolver,
) : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(userArgumentResolver)
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/static/**")
            .addResourceLocations("classpath:/static/")
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**") // 모든 경로에 대해
            .allowedOriginPatterns(
                "http://localhost:3000",                  // 로컬 프론트엔드
                "http://localhost:5173",                  // 로컬 프론트엔드 (Vite)
                "http://localhost:5174",                  // 로컬 프론트엔드 (Vite 대체 포트)
                "https://hangsha-dev.wafflestudio.com",   // Dev 프론트엔드
                "https://hangsha.wafflestudio.com",       // Prod 프론트엔드
                "https://*.app.github.dev"                // GitHub Codespaces 포워딩 도메인
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true) // 🌟 쿠키 주고받기 허용 (매우 중요)
    }
}
