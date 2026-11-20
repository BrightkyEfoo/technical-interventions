package cm.brightky.serviceopsjava.users.infrastructure.web;

import cm.brightky.accounts.KeycloakAccounts;
import cm.brightky.accounts.KeycloakAccounts.AccountPage;
import cm.brightky.accounts.KeycloakAccounts.StaffAccount;
import cm.brightky.accounts.KeycloakAccounts.StaffRole;
import cm.brightky.accounts.KeycloakAccounts.TechnicianSummary;
import cm.brightky.serviceopsjava.shared.error.ErrorCode;
import cm.brightky.serviceopsjava.shared.error.ServiceOpsException;
import cm.brightky.serviceopsjava.shared.security.ActorResolver;
import cm.brightky.serviceopsjava.users.application.port.out.UserRepository;
import cm.brightky.serviceopsjava.users.domain.Role;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AccountsController {
    private final ActorResolver actors;
    private final KeycloakAccounts accounts;
    private final UserRepository demoUsers;

    AccountsController(ActorResolver actors, KeycloakAccounts accounts, UserRepository demoUsers) {
        this.actors = actors;
        this.accounts = accounts;
        this.demoUsers = demoUsers;
    }

    @PostMapping("/admin/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un compte responsable ou technicien", description = "Réservé au super admin. Le mot de passe est temporaire.")
    @ApiResponse(responseCode = "201", description = "Compte créé",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StaffAccount.class)))
    @ApiResponse(responseCode = "400", description = "Données ou rôle invalides (INVALID_INPUT ou INVALID_ACCOUNT)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Session nécessaire", content = @Content)
    @ApiResponse(responseCode = "403", description = "Rôle SUPER_ADMIN requis (REQUEST_FORBIDDEN)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Identifiant ou courriel déjà utilisé (ACCOUNT_CONFLICT)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Annuaire indisponible (ACCOUNT_DIRECTORY_UNAVAILABLE)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    StaffAccount createStaff(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                             @Valid @RequestBody StaffRequest body) {
        actors.requireSuperAdmin(jwt);
        return accounts.createStaff(body.role(), body.username(), body.email(), body.firstName().strip(),
                body.lastName().strip(), body.temporaryPassword());
    }

    @GetMapping("/admin/accounts")
    @Operation(summary = "Lister les comptes utilisateurs", description = "Annuaire Keycloak réservé au super admin.")
    @ApiResponse(responseCode = "200", description = "Page de comptes",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AccountPage.class)))
    @ApiResponse(responseCode = "400", description = "Recherche ou pagination invalide",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Session nécessaire", content = @Content)
    @ApiResponse(responseCode = "403", description = "Rôle SUPER_ADMIN requis",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Annuaire indisponible",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<AccountPage> listAccounts(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(defaultValue = "") String query,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        actors.requireSuperAdmin(jwt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.listAccounts(query, page, size));
    }

    @GetMapping("/technicians")
    @Operation(summary = "Lister les techniciens actifs pour une affectation", description = "Réservé au responsable.")
    List<TechnicianSummary> technicians(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                      @RequestHeader(name = "X-Demo-Actor", required = false) UUID demoId) {
        if (actors.require(jwt, demoId).role() != Role.MANAGER) {
            throw new ServiceOpsException(ErrorCode.REQUEST_FORBIDDEN, "Only a manager can list technicians");
        }
        return accounts.isConfigured() ? accounts.technicians() : demoUsers.findTechnicians().stream()
                .map(user -> new TechnicianSummary(user.id(), user.name())).toList();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    ProblemDetail invalidAccount() {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Vérifie les champs du compte et le rôle demandé.");
        problem.setProperty("code", "INVALID_INPUT");
        return problem;
    }

    record StaffRequest(
            @NotNull StaffRole role,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,64}") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String firstName,
            @NotBlank @Size(max = 120) String lastName,
            @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
            @NotBlank @Size(min = 12, max = 128) String temporaryPassword) {
        @Override
        public String toString() {
            return "StaffRequest[redacted]";
        }
    }
}
