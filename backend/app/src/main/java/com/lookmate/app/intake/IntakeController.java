package com.lookmate.app.intake;

import com.lookmate.app.storage.BlobStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client intake, without an account (CLAUDE.md): the quiz, the inspiration upload, and the client's own view through
 * their link. Rate-limited per IP and per email (api/PublicRateLimitFilter); a wrong or expired token is always the same 404.
 */
@RestController
@RequestMapping("/api/public/match-requests")
class IntakeController {

    record PhotoFile(@NotBlank String contentType, @Positive long sizeBytes) {
    }

    record CreateRequest(@NotBlank @Email @Size(max = 254) String email,
                         @Size(max = 40) String firstName,
                         @NotNull Quiz.HairType hairType,
                         Quiz.Texture texture,
                         Quiz.Length length,
                         @Min(1) @Max(4) Integer budgetTier,
                         @Size(max = 80) String neighborhood,
                         @NotNull Quiz.Service service,
                         @Size(max = 10) List<Quiz.@NotNull PastIssue> pastIssues,
                         @Size(max = 500) String pastIssuesNote,
                         @NotNull @Valid PhotoFile inspiration) {
    }

    record UploadSlot(String url, String method, Map<String, String> headers, Instant expiresAt) {
        static UploadSlot of(BlobStore.PresignedUpload u) {
            return new UploadSlot(u.url().toString(), u.method(), u.headers(), u.expiresAt());
        }
    }

    record Created(String token, String statusPath, UploadSlot upload) {
    }

    private final IntakeService intake;

    IntakeController(IntakeService intake) {
        this.intake = intake;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@Valid @RequestBody CreateRequest body) {
        IntakeService.Created created = intake.create(new IntakeService.NewRequest(body.email(), body.firstName(), body.hairType(),
                body.texture(), body.length(), body.budgetTier(), body.neighborhood(), body.service(), body.pastIssues(), body.pastIssuesNote(),
                body.inspiration().contentType(), body.inspiration().sizeBytes()));
        return new Created(created.token(), created.statusPath(), UploadSlot.of(created.upload()));
    }

    @GetMapping("/{token}")
    IntakeService.ClientView view(@PathVariable String token) {
        return intake.view(token);
    }

    @PostMapping("/{token}/inspiration")
    UploadSlot uploadSlot(@PathVariable String token, @Valid @RequestBody PhotoFile body) {
        return UploadSlot.of(intake.uploadSlot(token, body.contentType(), body.sizeBytes()));
    }

    @PostMapping("/{token}/inspiration/confirm")
    IntakeService.ClientView confirm(@PathVariable String token) {
        return intake.confirm(token);
    }
}
