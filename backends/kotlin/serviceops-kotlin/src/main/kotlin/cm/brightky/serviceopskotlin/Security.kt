package cm.brightky.serviceopskotlin

import cm.brightky.accounts.KeycloakAccounts
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.web.SecurityFilterChain
import org.springframework.stereotype.Component
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.util.UUID

@Configuration
class ApiSecurity(
    private val environment: Environment,
    @Value("\${SERVICEOPS_ALLOWED_ORIGINS:http://localhost:4200,http://127.0.0.1:4200,http://localhost:8100,http://127.0.0.1:8100,capacitor://localhost,http://localhost,https://localhost}")
    private val origins: String,
) {
    @Bean
    fun keycloakAccounts(
        @Value("\${SERVICEOPS_ACCOUNTS_URL:http://localhost:8180}") url: String,
        @Value("\${SERVICEOPS_ACCOUNTS_CLIENT_SECRET:}") secret: String,
    ) = KeycloakAccounts(url, secret)

    @Bean
    fun configure(http: HttpSecurity): SecurityFilterChain {
        val demo = environment.acceptsProfiles(Profiles.of("demo"))
        val cors = CorsConfiguration()
        cors.allowedOrigins = origins.split(",").map(String::trim)
        cors.allowedMethods = listOf("GET", "POST", "OPTIONS")
        cors.allowedHeaders = listOf("Authorization", "Content-Type", "Idempotency-Key", "X-Demo-Actor")
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", cors)
        http.cors { it.configurationSource(source) }.csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        if (demo) {
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
        } else {
            http.authorizeHttpRequests {
                it.requestMatchers({ request -> request.localPort == 9091 && request.requestURI == "/actuator/prometheus" }).permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**",
                    "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                    .anyRequest().authenticated()
            }.oauth2ResourceServer {
                it.jwt(Customizer.withDefaults()).authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.setHeader("WWW-Authenticate", "Bearer")
                    response.contentType = "application/problem+json"
                    response.writer.write("""{"status":401,"code":"UNAUTHENTICATED","detail":"A valid bearer token is required"}""")
                }
            }
        }
        return http.build()
    }
}

@Component
class Actors(private val environment: Environment) {
    fun current(request: HttpServletRequest, demoId: UUID? = null): Account {
        val actor = resolve(request, demoId)
        if (actor.role == Role.SUPER_ADMIN) fail(Fault.REQUEST_FORBIDDEN, "The super admin has read-only access to requests")
        return actor
    }

    fun read(request: HttpServletRequest): Account = resolve(request, null)

    fun requireSuperAdmin() {
        if (resolve(null, null).role != Role.SUPER_ADMIN)
            fail(Fault.REQUEST_FORBIDDEN, "Only a super admin can manage accounts")
    }

    private fun resolve(request: HttpServletRequest?, demoId: UUID?): Account {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        if (principal is Jwt) {
            val realm = principal.claims["realm_access"] as? Map<*, *>
            val roles = realm?.get("roles") as? Collection<*>
            val role = Role.entries.firstOrNull { roles?.contains(it.name) == true }
            val subject = runCatching { UUID.fromString(principal.subject) }.getOrNull()
            if (role != null && subject != null) return Account(subject, role)
        }
        if (environment.acceptsProfiles(Profiles.of("demo"))) {
            val actorId = request?.getHeader("X-Demo-Actor")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: demoId
            if (actorId != null) return demoAccounts[actorId] ?: fail(Fault.USER_NOT_FOUND, "Unknown demo user")
        }
        fail(Fault.UNAUTHENTICATED, "An authenticated ServiceOps account is required")
    }
}
