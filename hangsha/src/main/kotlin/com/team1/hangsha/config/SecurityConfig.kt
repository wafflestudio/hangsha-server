package com.team1.hangsha.config

import com.team1.hangsha.user.JwtAuthenticationFilter
import com.team1.hangsha.user.handler.OAuth2SuccessHandler
import com.team1.hangsha.user.service.CustomOAuth2UserService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter


@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val customOAuth2UserService: CustomOAuth2UserService,
    private val oAuth2SuccessHandler: OAuth2SuccessHandler,
    private val httpCookieOAuth2AuthorizationRequestRepository: HttpCookieOAuth2AuthorizationRequestRepository,
    @Value("\${app.oauth2.front-redirect-uri}")
    private val frontRedirectUri: String
) {
    @Bean
    fun jwtFilterRegistration(jwtAuthenticationFilter: JwtAuthenticationFilter)
            : FilterRegistrationBean<JwtAuthenticationFilter> {
        return FilterRegistrationBean(jwtAuthenticationFilter).apply {
            isEnabled = false
        }
    }

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { }
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, authException ->
                    response.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, authException.message)
                }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }

            .authorizeHttpRequests { auth ->
                auth
                    // public path
                    .requestMatchers(
                        // 문서
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/api-docs/**",
                        "/api/v1/auth/**",
                        "/api/v1/auth/login/social",
                        "/openapi.yaml/**",
                        "/api/v1/health",
                        // 행사
                        "/api/v1/events/month",
                        "/api/v1/events/month/**",
                        "/api/v1/events/day",
                        "/api/v1/events/day/**",
                        "/api/v1/events/search/**",
                        "/api/v1/events/**",
                        // 주최 기관
                        "/api/v1/category-groups/**",
                        "/api/v1/categories/**",
                        // 파일 업로드
                        "/static/**",
                        "/api/v1/uploads/oci/**",
                        "/oauth2/authorization/**",
                        "/login/oauth2/code/**",
                    ).permitAll()
                    .requestMatchers("/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)

            .oauth2Login { oauth2 ->
                oauth2.authorizationEndpoint { endpoint ->
                    endpoint.authorizationRequestRepository(httpCookieOAuth2AuthorizationRequestRepository)
                }
                oauth2.userInfoEndpoint { it.userService(customOAuth2UserService) }
                oauth2.successHandler(oAuth2SuccessHandler)
                oauth2.failureHandler { request, response, exception ->
                    httpCookieOAuth2AuthorizationRequestRepository.removeAuthorizationRequestCookies(request, response)

                    val homeUrl = frontRedirectUri.replace("/auth/callback", "/")
                    response.sendRedirect(homeUrl)
                }
            }


        return http.build()
    }
}
