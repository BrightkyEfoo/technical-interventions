package cm.brightky.serviceopsjava.requests.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RequestControllerTest {
    private static final String REQUESTS_PATH = "/api/v1/requests";
    private static final String UUID_PATTERN = "(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";

    private final MockMvc mvc;

    @Autowired
    RequestControllerTest(WebApplicationContext context) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void createsRequestAndReturns201WithJsonBody() throws Exception {
        mvc.perform(post(REQUESTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  Porte bloquée  "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.title").value("Porte bloquée"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.assigneeId").hasJsonPath())
                .andExpect(jsonPath("$.assigneeId").value(nullValue()));
    }

    @Test
    void rejectsMalformedJsonWith400() throws Exception {
        mvc.perform(post(REQUESTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsBlankTitleWith400() throws Exception {
        mvc.perform(post(REQUESTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"   "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @MethodSource("invalidTitles")
    void returnsProblemDetailsForInvalidTitleLength(String title, String detail) throws Exception {
        mvc.perform(post(REQUESTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s"}
                                """.formatted(title)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    @Test
    void documentsCreationAndInvalidTitleResponsesInOpenApi() throws Exception {
        String responses = "$.paths['" + REQUESTS_PATH + "'].post.responses";
        String created = responses + "['201'].content['application/json']";
        String badRequest = responses + "['400'].content['application/problem+json']";
        String schemas = "$.components.schemas";

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(created + ".schema['$ref']").value("#/components/schemas/RequestResponse"))
                .andExpect(jsonPath(schemas + ".RequestResponse.properties.assigneeId.type").value(hasItem("null")))
                .andExpect(jsonPath(badRequest + ".schema['$ref']").value("#/components/schemas/RequestProblemResponse"))
                .andExpect(jsonPath(schemas + ".RequestProblemResponse.properties.instance.format").value("uri-reference"))
                .andExpect(jsonPath(schemas + ".RequestProblemResponse.required").value(hasItem("code")))
                .andExpect(jsonPath(schemas + ".RequestProblemResponse.properties.code.type").value("string"))
                .andExpect(jsonPath(badRequest + ".examples.invalidTitle.value.code").value("INVALID_TITLE"));
    }

    private static Stream<Arguments> invalidTitles() {
        return Stream.of(
                Arguments.of("abcd", "The title must have at least 5 characters"),
                Arguments.of("  LED  ", "The title must have at least 5 characters"),
                Arguments.of("x".repeat(121), "The title must have at most 120 characters")
        );
    }
}
