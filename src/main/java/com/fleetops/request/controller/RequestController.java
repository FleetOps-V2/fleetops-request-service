package com.fleetops.request.controller;

import com.fleetops.request.entity.ServiceRequest;
import com.fleetops.request.entity.ServiceRequest.RequestStatus;
import com.fleetops.request.exception.DownstreamServiceException;
import com.fleetops.request.service.ServiceRequestService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requests")
public class RequestController {

    private final ServiceRequestService requestService;

    public RequestController(ServiceRequestService requestService) {
        this.requestService = requestService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('DRIVER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<List<ServiceRequest>> getRequests(Authentication authentication) {
        boolean isDriver = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DRIVER"));

        if (isDriver) {
            return ResponseEntity.ok(requestService.getRequestsByRequestedBy(authentication.getName()));
        }

        return ResponseEntity.ok(requestService.getAllRequests());
    }

    @GetMapping("/vehicle/{vehicleId}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<List<ServiceRequest>> getRequestsByVehicle(@PathVariable Long vehicleId) {
        return ResponseEntity.ok(requestService.getRequestsByVehicle(vehicleId));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('DRIVER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<ServiceRequest> getRequest(@PathVariable Long id, Authentication authentication) {
        return requestService.getRequestById(id)
                .map(request -> {
                    boolean isDriver = authentication.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_DRIVER"));
                    
                    if (isDriver && !request.getRequestedBy().equals(authentication.getName())) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).<ServiceRequest>build();
                    }
                    return ResponseEntity.ok(request);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('DRIVER', 'ADMIN')")
    public ResponseEntity<?> createRequest(@RequestBody ServiceRequest request, Authentication authentication, HttpServletRequest httpRequest) {
        try {
            String token = extractToken(httpRequest);
            boolean isDriver = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_DRIVER"));
            ServiceRequest created = requestService.createRequest(request, authentication.getName(), token, isDriver);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalStateException e) {
            return buildError(HttpStatus.CONFLICT, e.getMessage(), httpRequest.getRequestURI());
        } catch (IllegalArgumentException e) {
            return buildError(HttpStatus.BAD_REQUEST, e.getMessage(), httpRequest.getRequestURI());
        } catch (DataIntegrityViolationException e) {
            return buildError(HttpStatus.CONFLICT, "Vehicle already has an active service request.", httpRequest.getRequestURI());
        } catch (DownstreamServiceException e) {
            return buildError(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), httpRequest.getRequestURI());
        }
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> payload, Authentication authentication, HttpServletRequest httpRequest) {
        if (!payload.containsKey("status") || payload.get("status") == null) {
            return buildError(HttpStatus.BAD_REQUEST, "Missing 'status' field", httpRequest.getRequestURI());
        }

        try {
            RequestStatus newStatus = RequestStatus.valueOf(payload.get("status").toUpperCase());
            String token = extractToken(httpRequest);
            
            return requestService.updateRequestStatus(id, newStatus, authentication.getName(), token)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(buildError(HttpStatus.NOT_FOUND, "Request not found", httpRequest.getRequestURI()));
        } catch (IllegalArgumentException e) {
            return buildError(HttpStatus.BAD_REQUEST, "Invalid status", httpRequest.getRequestURI());
        } catch (IllegalStateException e) {
            return buildError(HttpStatus.CONFLICT, e.getMessage(), httpRequest.getRequestURI());
        } catch (DownstreamServiceException e) {
            return buildError(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), httpRequest.getRequestURI());
        }
    }

    @PatchMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<?> assignTechnician(@PathVariable Long id,
                                              @RequestBody Map<String, String> payload,
                                              HttpServletRequest httpRequest) {
        if (!payload.containsKey("technician")) {
            return buildError(HttpStatus.BAD_REQUEST, "Missing 'technician' field", httpRequest.getRequestURI());
        }
        try {
            String token = extractToken(httpRequest);
            return requestService.assignTechnician(id, payload.get("technician"), token)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(buildError(HttpStatus.NOT_FOUND, "Request not found", httpRequest.getRequestURI()));
        } catch (IllegalStateException e) {
            return buildError(HttpStatus.CONFLICT, e.getMessage(), httpRequest.getRequestURI());
        } catch (DownstreamServiceException e) {
            return buildError(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), httpRequest.getRequestURI());
        }
    }
    
    @PatchMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ResponseEntity<?> completeRequest(@PathVariable Long id, @RequestBody Map<String, Object> payload, HttpServletRequest httpRequest) {
        String token = extractToken(httpRequest);
        String resolutionNotes = (String) payload.get("resolutionNotes");
        Double downtimeHours = payload.containsKey("downtimeHours") ? Double.valueOf(payload.get("downtimeHours").toString()) : null;
        
        try {
            return requestService.completeRequest(id, resolutionNotes, downtimeHours, token)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(buildError(HttpStatus.NOT_FOUND, "Request not found", httpRequest.getRequestURI()));
        } catch (IllegalStateException e) {
            return buildError(HttpStatus.CONFLICT, e.getMessage(), httpRequest.getRequestURI());
        } catch (IllegalArgumentException e) {
            return buildError(HttpStatus.BAD_REQUEST, e.getMessage(), httpRequest.getRequestURI());
        } catch (DownstreamServiceException e) {
             return buildError(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), httpRequest.getRequestURI());
        }
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null) return authHeader;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("jwt".equals(cookie.getName())) return "Bearer " + cookie.getValue();
            }
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", path);
        return ResponseEntity.status(status).body(body);
    }
}

