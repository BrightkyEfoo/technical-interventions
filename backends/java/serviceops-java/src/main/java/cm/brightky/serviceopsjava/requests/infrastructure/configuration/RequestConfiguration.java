package cm.brightky.serviceopsjava.requests.infrastructure.configuration;

import cm.brightky.serviceopsjava.requests.application.AssignRequest;
import cm.brightky.serviceopsjava.requests.application.CreateRequest;
import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.infrastructure.persistence.InMemoryRequestRepository;
import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class RequestConfiguration {

    @Bean
    @Profile("!postgres")
    public RequestRepository requestRepository() {
        return new InMemoryRequestRepository();
    }

    @Bean
    public CreateRequest createRequest(RequestRepository requestRepository) {
        return new CreateRequest(requestRepository);
    }

    @Bean
    public AssignRequest assignRequest(RequestRepository requestRepository, UserRepository userRepository) {
        return new AssignRequest(requestRepository, userRepository);
    }
}
