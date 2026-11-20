package cm.brightky.serviceopsjava.requests.application;

import cm.brightky.serviceopsjava.requests.application.port.out.RequestRepository;
import cm.brightky.serviceopsjava.requests.domain.Request;
import cm.brightky.serviceopsjava.requests.domain.RequestId;
import cm.brightky.serviceopsjava.requests.domain.RequestStatus;
import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;
import cm.brightky.serviceopsjava.users.domain.Manager;
import cm.brightky.serviceopsjava.users.domain.Technician;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AssignRequestTest {
    private final RequestRepository requestRepository = mock(RequestRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AssignRequest assignRequest = new AssignRequest(requestRepository, userRepository);

    private final Request request = new Request(new RequestId(UUID.randomUUID()), "Porte bloquée");
    private final Manager actor = new Manager(UUID.randomUUID(), "Responsable");
    private final Technician technician = new Technician(UUID.randomUUID(), "Technicien");
    private final AssignRequestCommand command = new AssignRequestCommand(request.id(), actor.id(), technician.id());

    @Test
    void savesRequestAfterApplyingAssignment() {
        when(requestRepository.findById(command.requestId())).thenReturn(Optional.of(request));
        when(userRepository.findById(command.actorId())).thenReturn(Optional.of(actor));
        when(userRepository.findById(command.technicianId())).thenReturn(Optional.of(technician));
        when(requestRepository.save(request)).thenAnswer(invocation -> {
            Request saved = invocation.getArgument(0);
            assertEquals(RequestStatus.ASSIGNED, saved.status());
            assertEquals(technician.id(), saved.assigneeId());
            return saved;
        });

        assignRequest.execute(command);

        verify(requestRepository).save(request);
    }

    @Test
    void reportsUnknownRequestBeforeLookingUpUsers() {
        when(requestRepository.findById(command.requestId())).thenReturn(Optional.empty());

        var problem = assertThrows(ServiceOpsException.class, () -> assignRequest.execute(command));

        assertEquals(ErrorCode.REQUEST_NOT_FOUND, problem.code());
        assertEquals("Request not found: " + command.requestId().value(), problem.getMessage());
        verifyNoInteractions(userRepository);
        verify(requestRepository, never()).save(any());
    }

    @Test
    void reportsUnknownActorBeforeChangingRequest() {
        when(requestRepository.findById(command.requestId())).thenReturn(Optional.of(request));
        when(userRepository.findById(command.actorId())).thenReturn(Optional.empty());

        var problem = assertThrows(ServiceOpsException.class, () -> assignRequest.execute(command));

        assertEquals(ErrorCode.USER_NOT_FOUND, problem.code());
        assertEquals("Actor not found: " + command.actorId(), problem.getMessage());
        assertEquals(RequestStatus.OPEN, request.status());
        assertNull(request.assigneeId());
        verify(requestRepository, never()).save(any());
    }

    @Test
    void reportsUnknownTechnicianBeforeChangingRequest() {
        when(requestRepository.findById(command.requestId())).thenReturn(Optional.of(request));
        when(userRepository.findById(command.actorId())).thenReturn(Optional.of(actor));
        when(userRepository.findById(command.technicianId())).thenReturn(Optional.empty());

        var problem = assertThrows(ServiceOpsException.class, () -> assignRequest.execute(command));

        assertEquals(ErrorCode.USER_NOT_FOUND, problem.code());
        assertEquals("Technician not found: " + command.technicianId(), problem.getMessage());
        assertEquals(RequestStatus.OPEN, request.status());
        assertNull(request.assigneeId());
        verify(requestRepository, never()).save(any());
    }
}
