package com.lookmate.app.intake;

import com.lookmate.app.stylists.HairType;
import com.lookmate.app.stylists.ServiceType;
import com.lookmate.app.stylists.StylistDirectory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The stylist directory as a client with a link sees it: every LIVE stylist in their request's market, the same page as
 * the open {@code /api/public/stylists} (stylists module). The client page uses this one, so browsing is tied to the
 * request (its Book links go through tracking with the same token). A wrong or expired token is the usual 404.
 */
@RestController
class ClientDirectoryController {

    private final MatchRequests requests;
    private final StylistDirectory directory;

    ClientDirectoryController(MatchRequests requests, StylistDirectory directory) {
        this.requests = requests;
        this.directory = directory;
    }

    @GetMapping(IntakeController.BASE + "/{token}/stylists")
    ResponseEntity<StylistDirectory.DirectoryPage> stylists(@PathVariable String token,
                                                            @RequestParam(required = false) ServiceType service,
                                                            @RequestParam(required = false) HairType hairType,
                                                            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page) {
        MatchRequests.Request request = requests.forToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(directory.page(request.market(), service, hairType, page));
    }
}
