package cm.brightky.serviceopsjava.requests.domain;

import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.users.domain.Manager;
import cm.brightky.serviceopsjava.users.domain.Role;
import cm.brightky.serviceopsjava.users.domain.Technician;
import cm.brightky.serviceopsjava.users.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RequestTest {
    private Request makeRequestFromTitle(String title) {
        RequestId id = new RequestId(UUID.randomUUID());
        return new Request(id, title);
    }

    @Test
    public void newRequestStartsOpen() {
        Request request = this.makeRequestFromTitle("Besoin de reparer mon ampoule");

        assertEquals(RequestStatus.OPEN, request.status());
    }

    @Test
    public void rejectsTitleShorterThanFiveCharactersAfterStripping() {
        ServiceOpsException exception = assertThrows(ServiceOpsException.class, () -> {
            this.makeRequestFromTitle("  LED  ");
        });

        assertEquals(ErrorCode.INVALID_TITLE, exception.code());
        assertEquals("The title must have at least 5 characters", exception.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 121})
    void rejectsTitleOutsideAllowedLength(int length) {
        String title = "x".repeat(length);

        ServiceOpsException exception = assertThrows(ServiceOpsException.class, () -> this.makeRequestFromTitle(title));

        assertEquals(ErrorCode.INVALID_TITLE, exception.code());
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 120})
    void acceptsTitleAtAllowedLengthBoundaries(int length) {
        String title = "x".repeat(length);
        Request request = this.makeRequestFromTitle("  " + title + "  ");

        assertEquals(title, request.title());
    }

    @Test
    void managerAssignsOpenRequestToTechnician() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        Technician technician = new Technician(UUID.randomUUID(), "Technicien");

        manager.assignRequest(request, technician);

        assertEquals(RequestStatus.ASSIGNED, request.status());
        assertEquals(technician.id(), request.assigneeId());
    }

    @Test
    void requesterCannotAssignOpenRequest() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        User requester = new User(UUID.randomUUID(), "Demandeur", Role.REQUESTER);
        Technician technician = new Technician(UUID.randomUUID(), "Technicien");

        var error = assertThrows(ServiceOpsException.class, () -> request.assignTo(requester, technician));
        assertEquals(ErrorCode.ASSIGNMENT_FORBIDDEN, error.code());
        assertEquals(RequestStatus.OPEN, request.status());
        assertNull(request.assigneeId());
    }

    @Test
    void managerCannotAssignRequestToNonTechnician() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        User requester = new User(UUID.randomUUID(), "Demandeur", Role.REQUESTER);

        var error = assertThrows(ServiceOpsException.class, () -> request.assignTo(manager, requester));
        assertEquals(ErrorCode.INVALID_ASSIGNEE, error.code());
        assertEquals(RequestStatus.OPEN, request.status());
        assertNull(request.assigneeId());
    }

    @Test
    void managerCannotClearExistingAssignmentByPassingNull() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        Technician technician = new Technician(UUID.randomUUID(), "Technicien");
        manager.assignRequest(request, technician);

        var error = assertThrows(ServiceOpsException.class, () -> manager.assignRequest(request, null));
        assertEquals(ErrorCode.INVALID_ASSIGNEE, error.code());
        assertEquals(RequestStatus.ASSIGNED, request.status());
        assertEquals(technician.id(), request.assigneeId());
    }

    @Test
    void managerCanReassignRequestBeforeWorkStarts() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        Technician firstTechnician = new Technician(UUID.randomUUID(), "Premier technicien");
        Technician secondTechnician = new Technician(UUID.randomUUID(), "Second technicien");
        manager.assignRequest(request, firstTechnician);

        manager.assignRequest(request, secondTechnician);

        assertEquals(RequestStatus.ASSIGNED, request.status());
        assertEquals(secondTechnician.id(), request.assigneeId());
    }

    @Test
    void assignedTechnicianStartsRequestWithRecreatedUuid() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        Technician assignedTechnician = new Technician(UUID.randomUUID(), "Technicien affecté");
        manager.assignRequest(request, assignedTechnician);
        UUID recreatedId = UUID.fromString(assignedTechnician.id().toString());
        Technician actor = new Technician(recreatedId, "Technicien affecté");

        request.start(actor);

        assertEquals(RequestStatus.IN_PROGRESS, request.status());
        assertEquals(assignedTechnician.id(), request.assigneeId());
    }

    @Test
    void otherTechnicianCannotStartAssignedRequest() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        Technician assignedTechnician = new Technician(UUID.randomUUID(), "Technicien affecté");
        Technician otherTechnician = new Technician(UUID.randomUUID(), "Autre technicien");
        manager.assignRequest(request, assignedTechnician);

        assertThrows(IllegalArgumentException.class, () -> request.start(otherTechnician));
        assertEquals(RequestStatus.ASSIGNED, request.status());
        assertEquals(assignedTechnician.id(), request.assigneeId());
    }

    @Test
    void managerCannotStartRequestEvenWithAssignedUuid() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        Technician assignedTechnician = new Technician(UUID.randomUUID(), "Technicien affecté");
        manager.assignRequest(request, assignedTechnician);
        User actor = new User(assignedTechnician.id(), "Responsable", Role.MANAGER);

        assertThrows(IllegalArgumentException.class, () -> request.start(actor));
        assertEquals(RequestStatus.ASSIGNED, request.status());
        assertEquals(assignedTechnician.id(), request.assigneeId());
    }

    @Test
    void technicianCannotStartOpenRequest() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Technician technician = new Technician(UUID.randomUUID(), "Technicien");

        assertThrows(IllegalArgumentException.class, () -> request.start(technician));
        assertEquals(RequestStatus.OPEN, request.status());
        assertNull(request.assigneeId());
    }

    @Test
    void assignedTechnicianCannotStartRequestTwice() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        Technician assignedTechnician = new Technician(UUID.randomUUID(), "Technicien affecté");
        manager.assignRequest(request, assignedTechnician);
        request.start(assignedTechnician);

        assertThrows(IllegalStateException.class, () -> request.start(assignedTechnician));
        assertEquals(RequestStatus.IN_PROGRESS, request.status());
        assertEquals(assignedTechnician.id(), request.assigneeId());
    }

    @Test
    void managerCannotReassignRequestAfterWorkStarts() {
        Request request = this.makeRequestFromTitle("Porte bloquée");
        Manager manager = new Manager(UUID.randomUUID(), "Responsable");
        Technician assignedTechnician = new Technician(UUID.randomUUID(), "Technicien affecté");
        Technician otherTechnician = new Technician(UUID.randomUUID(), "Autre technicien");
        manager.assignRequest(request, assignedTechnician);
        request.start(assignedTechnician);

        var error = assertThrows(ServiceOpsException.class, () -> manager.assignRequest(request, otherTechnician));
        assertEquals(ErrorCode.REQUEST_ASSIGNMENT_CONFLICT, error.code());
        assertEquals(RequestStatus.IN_PROGRESS, request.status());
        assertEquals(assignedTechnician.id(), request.assigneeId());
    }

    @Test
    void requestKeepRequestId() {
        RequestId id = new RequestId(UUID.randomUUID());
        Request request = new Request(id, "Porte Bloquée");

        assertEquals(id, request.id());
        assertThrows(IllegalArgumentException.class, ()-> new Request(null, "Porte bloquée"));
        assertThrows(IllegalArgumentException.class, ()-> new Request(new RequestId(null), "Porte bloquée"));
    }
}
