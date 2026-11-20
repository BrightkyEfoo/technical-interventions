package cm.brightky.serviceopskotlin

import cm.brightky.accounts.KeycloakAccounts
import cm.brightky.accounts.KeycloakAccounts.AccountPage
import cm.brightky.accounts.KeycloakAccounts.StaffAccount
import cm.brightky.accounts.KeycloakAccounts.StaffRole
import cm.brightky.accounts.KeycloakAccounts.TechnicianSummary
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class AccountsController(private val actors: Actors, private val accounts: KeycloakAccounts) {

    @PostMapping("/admin/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un compte responsable ou technicien", description = "Réservé au super admin. Le mot de passe est temporaire.")
    @ApiResponse(responseCode = "201", description = "Compte créé",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = StaffAccount::class))])
    @ApiResponse(responseCode = "400", description = "Données ou rôle invalides (INVALID_INPUT ou INVALID_ACCOUNT)",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))])
    @ApiResponse(responseCode = "401", description = "Session nécessaire", content = [Content()])
    @ApiResponse(responseCode = "403", description = "Rôle SUPER_ADMIN requis (REQUEST_FORBIDDEN)",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))])
    @ApiResponse(responseCode = "409", description = "Identifiant ou courriel déjà utilisé (ACCOUNT_CONFLICT)",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))])
    @ApiResponse(responseCode = "503", description = "Annuaire indisponible (ACCOUNT_DIRECTORY_UNAVAILABLE)",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))])
    fun createStaff(@Valid @RequestBody body: StaffRequest): StaffAccount {
        actors.requireSuperAdmin()
        return accounts.createStaff(body.role, body.username, body.email, body.firstName.trim(),
            body.lastName.trim(), body.temporaryPassword)
    }

    @GetMapping("/admin/accounts")
    @Operation(summary = "Lister les comptes utilisateurs", description = "Annuaire Keycloak réservé au super admin.")
    @ApiResponse(responseCode = "200", description = "Page de comptes",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = AccountPage::class))])
    @ApiResponse(responseCode = "400", description = "Recherche ou pagination invalide",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))])
    @ApiResponse(responseCode = "401", description = "Session nécessaire", content = [Content()])
    @ApiResponse(responseCode = "403", description = "Rôle SUPER_ADMIN requis",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))])
    @ApiResponse(responseCode = "503", description = "Annuaire indisponible",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))])
    fun listAccounts(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<AccountPage> {
        actors.requireSuperAdmin()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.listAccounts(query, page, size))
    }

    @GetMapping("/technicians")
    @Operation(summary = "Lister les techniciens actifs pour une affectation", description = "Réservé au responsable.")
    fun technicians(request: HttpServletRequest): List<TechnicianSummary> {
        if (actors.current(request).role != Role.MANAGER)
            fail(Fault.REQUEST_FORBIDDEN, "Only a manager can list technicians")
        if (accounts.isConfigured) return accounts.technicians()
        return demoAccounts.values.filter { it.role == Role.TECHNICIAN }
            .map { TechnicianSummary(it.id, it.username) }.sortedBy { it.name() }
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentNotValidException::class)
    fun invalidAccount(): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Vérifie les champs du compte et le rôle demandé.").apply {
            setProperty("code", "INVALID_INPUT")
        }
}

data class StaffRequest(
    val role: StaffRole,
    @field:NotBlank @field:Pattern(regexp = "[a-zA-Z0-9._-]{3,64}") val username: String,
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(max = 120) val firstName: String,
    @field:NotBlank @field:Size(max = 120) val lastName: String,
    @field:Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
    @field:NotBlank @field:Size(min = 12, max = 128) val temporaryPassword: String,
) {
    override fun toString() = "StaffRequest[redacted]"
}
