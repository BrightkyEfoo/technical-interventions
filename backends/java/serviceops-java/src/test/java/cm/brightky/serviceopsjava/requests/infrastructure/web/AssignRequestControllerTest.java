package cm.brightky.serviceopsjava.requests.infrastructure.web;

import cm.brightky.serviceopsjava.requests.application.CreateRequest;
import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.domain.RequestStatus;
import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("demo")
class AssignRequestControllerTest {
    private static final String ASSIGN_PATH = "/api/v1/requests/{id}/assign";
    private static final UUID MANAGER_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID TECHNICIAN_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private final MockMvc mvc;
    private final CreateRequest createRequest;
    private final RequestRepository requests;
    private final UserRepository users;

    @Autowired
    AssignRequestControllerTest(WebApplicationContext context, CreateRequest createRequest,
                                RequestRepository requests, UserRepository users) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
        this.createRequest = createRequest;
        this.requests = requests;
        this.users = users;
    }

    @Test
    void assignsRequestAndReturns204WithoutBody() throws Exception {
        var request = createRequest.execute("Porte bloquée");

        mvc.perform(assign(request.id().value(), MANAGER_ID, TECHNICIAN_ID))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        var saved = requests.findById(request.id()).orElseThrow();
        assertEquals(RequestStatus.ASSIGNED, saved.status());
        assertEquals(TECHNICIAN_ID, saved.assigneeId());
    }

    @Test
    void returns404WithRequestNotFoundCodeForUnknownRequest() throws Exception {
        mvc.perform(assign(UUID.randomUUID(), MANAGER_ID, TECHNICIAN_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidRequestIdWith400() throws Exception {
        mvc.perform(assign("not-a-uuid", MANAGER_ID, TECHNICIAN_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingTechnicianIdWith400() throws Exception {
        var request = createRequest.execute("Porte bloquée");

        mvc.perform(post(ASSIGN_PATH, request.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actorId":"%s"}
                                """.formatted(MANAGER_ID)))
                .andExpect(status().isBadRequest());

        assertEquals(RequestStatus.OPEN, request.status());
        assertNull(request.assigneeId());
    }

    @ParameterizedTest
    @MethodSource("invalidAssignments")
    void rejectsInvalidAssignmentWithoutChangingRequest(UUID actorId, UUID technicianId,
                                                         int expectedStatus, String code) throws Exception {
        var request = createRequest.execute("Porte bloquée");

        mvc.perform(assign(request.id().value(), actorId, technicianId))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code));

        assertEquals(RequestStatus.OPEN, request.status());
        assertNull(request.assigneeId());
    }

    @Test
    void returns409WhenWorkHasStarted() throws Exception {
        var request = createRequest.execute("Porte bloquée");
        var manager = users.findById(MANAGER_ID).orElseThrow();
        var technician = users.findById(TECHNICIAN_ID).orElseThrow();
        request.assignTo(manager, technician);
        request.start(technician);

        mvc.perform(assign(request.id().value(), MANAGER_ID, TECHNICIAN_ID))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_ASSIGNMENT_CONFLICT"));

        assertEquals(RequestStatus.IN_PROGRESS, request.status());
        assertEquals(TECHNICIAN_ID, request.assigneeId());
    }

    @Test
    void documentsAssignmentInOpenApi() throws Exception {
        String operation = "$.paths['" + ASSIGN_PATH + "'].post";
        String responses = operation + ".responses";

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".parameters[0].schema.format").value("uuid"))
                .andExpect(jsonPath("$.components.schemas.AssignRequestBody.required")
                        .value(hasItems("technicianId")))
                .andExpect(jsonPath(responses + "['204'].content").doesNotHaveJsonPath())
                .andExpect(jsonPath(responses + "['404'].content['application/problem+json'].schema['$ref']")
                        .value("#/components/schemas/RequestProblemResponse"));
    }

    private static Stream<Arguments> invalidAssignments() {
        return Stream.of(
                Arguments.of(UUID.randomUUID(), TECHNICIAN_ID, 404, "USER_NOT_FOUND"),
                Arguments.of(MANAGER_ID, UUID.randomUUID(), 404, "USER_NOT_FOUND"),
                Arguments.of(TECHNICIAN_ID, TECHNICIAN_ID, 403, "ASSIGNMENT_FORBIDDEN"),
                Arguments.of(MANAGER_ID, MANAGER_ID, 400, "INVALID_ASSIGNEE")
        );
    }

    private static MockHttpServletRequestBuilder assign(Object requestId, UUID actorId, UUID technicianId) {
        return post(ASSIGN_PATH, requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"actorId":"%s","technicianId":"%s"}
                        """.formatted(actorId, technicianId));
    }
}
