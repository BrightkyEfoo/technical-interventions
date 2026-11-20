package cm.brightky.serviceopsjava;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(title = "ServiceOps Lab", version = "1.0"),
        security = @SecurityRequirement(name = "oidcBearer"),
        servers = {@Server(url = "http://127.0.0.1:8080", description = "Java"),
                @Server(url = "http://127.0.0.1:8081", description = "Kotlin")})
@SecurityScheme(name = "oidcBearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class ServiceopsJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceopsJavaApplication.class, args);
    }
}
