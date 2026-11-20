package cm.brightky.serviceopsjava.shared.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;
import java.util.Arrays;

@Configuration
public class ApiSecurity {
    private final List<String> origins;

    ApiSecurity(@Value("${SERVICEOPS_ALLOWED_ORIGINS:http://localhost:4200,http://127.0.0.1:4200,http://localhost:8100,http://127.0.0.1:8100,capacitor://localhost,http://localhost,https://localhost}")
                String origins) {
        this.origins = Arrays.stream(origins.split(",")).map(String::strip).toList();
    }
    @Bean
    @Profile("!demo & !memory")
    SecurityFilterChain authenticatedApi(HttpSecurity http) throws Exception {
        return common(http)
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(request -> request.getLocalPort() == 9091 &&
                                "/actuator/prometheus".equals(request.getRequestURI())).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**",
                                "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            problem(response, 401, "UNAUTHENTICATED");
                        }))
                .exceptionHandling(errors -> errors
                        .accessDeniedHandler((request, response, failure) -> problem(response, 403, "REQUEST_FORBIDDEN")))
                .build();
    }

    @Bean
    @Profile("demo | memory")
    SecurityFilterChain localApi(HttpSecurity http) throws Exception {
        return common(http).authorizeHttpRequests(rules -> rules.anyRequest().permitAll()).build();
    }

    private HttpSecurity common(HttpSecurity http) throws Exception {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Demo-Actor"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return http.cors(options -> options.configurationSource(source))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    }

    private void problem(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        String detail = status == 401 ? "A valid bearer token is required" : "You cannot perform this action";
        response.getWriter().printf("{\"status\":%d,\"code\":\"%s\",\"detail\":\"%s\"}", status, code, detail);
    }
}
