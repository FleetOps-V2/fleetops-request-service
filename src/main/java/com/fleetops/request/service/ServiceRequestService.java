package com.fleetops.request.service;

import com.fleetops.request.entity.ServiceRequest;
import com.fleetops.request.entity.ServiceRequest.Priority;
import com.fleetops.request.entity.ServiceRequest.RequestStatus;
import com.fleetops.request.exception.DownstreamServiceException;
import com.fleetops.request.repository.ServiceRequestRepository;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ServiceRequestService {

    private static final Logger log = LoggerFactory.getLogger(ServiceRequestService.class);
    private static final List<RequestStatus> ACTIVE_STATUSES = List.of(
            RequestStatus.OPEN,
            RequestStatus.PENDING_APPROVAL,
            RequestStatus.APPROVED,
            RequestStatus.ASSIGNED,
            RequestStatus.IN_PROGRESS
    );

    private final ServiceRequestRepository repository;
    private final RestTemplate restTemplate;
    private final TransactionTemplate transactionTemplate;
    
    @Value("${app.vehicle-service-url}")
    private String vehicleServiceUrl;

    @Value("${app.maintenance-service-url}")
    private String maintenanceServiceUrl;

    public ServiceRequestService(ServiceRequestRepository repository, RestTemplate restTemplate, TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.restTemplate = restTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public List<ServiceRequest> getAllRequests() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public List<ServiceRequest> getRequestsByRequestedBy(String username) {
        return repository.findByRequestedByOrderByCreatedAtDesc(username);
    }

    public List<ServiceRequest> getRequestsByVehicle(Long vehicleId) {
        return repository.findByVehicleIdOrderByCreatedAtDesc(vehicleId);
    }

    public Optional<ServiceRequest> getRequestById(Long id) {
        return repository.findById(id);
    }

    public ServiceRequest createRequest(ServiceRequest request, String requestedBy, String token, boolean driverRequest) {
        if (request.getVehicleId() == null) {
            throw new IllegalStateException("Vehicle ID is required.");
        }

        Map<String, Object> vehicle = getVehicle(request.getVehicleId(), token);
        String vehicleStatus = stringValue(vehicle.get("status"));
        String assignedDriverId = stringValue(vehicle.get("assignedDriverId"));

        ensureVehicleNotRetired(vehicleStatus);
        if (driverRequest && (assignedDriverId == null || !assignedDriverId.equals(requestedBy))) {
            throw new IllegalStateException("Drivers can only create requests for assigned vehicles.");
        }

        if (!"ACTIVE".equals(vehicleStatus)) {
            throw new IllegalStateException("Requests can only be created for ACTIVE vehicles.");
        }

        applyRequestTypeRules(request, vehicle);
        request.setRequestedBy(requestedBy);
        request.setStatus(RequestStatus.OPEN);

        ServiceRequest savedRequest = transactionTemplate.execute(status -> {
            if (!repository.findActiveRequestsForUpdate(request.getVehicleId(), ACTIVE_STATUSES).isEmpty()) {
                throw new IllegalStateException("Vehicle already has an active service request.");
            }
            return repository.save(request);
        });

        if (request.getRequestType() == ServiceRequest.RequestType.BREAKDOWN) {
            updateVehicleStatus(request.getVehicleId(), "BREAKDOWN", token);
        }

        return savedRequest;
    }

    public Optional<ServiceRequest> updateRequestStatus(Long id, RequestStatus newStatus, String updatedBy, String token) {
        return repository.findById(id).map(request -> {
            Map<String, Object> vehicle = getVehicle(request.getVehicleId(), token);
            ensureVehicleNotRetired(stringValue(vehicle.get("status")));
            
            ServiceRequest updatedRequest = transactionTemplate.execute(status -> {
                ServiceRequest req = repository.findById(id).orElseThrow();
                RequestStatus oldStatus = req.getStatus();
                ensureMutable(oldStatus);
                ensureValidTransition(oldStatus, newStatus);

                if (newStatus == RequestStatus.APPROVED) {
                    req.setApprovedBy(updatedBy);
                }
                req.setStatus(newStatus);
                return repository.save(req);
            });

            try {
                if (newStatus == RequestStatus.IN_PROGRESS) {
                    updateVehicleStatus(request.getVehicleId(), "IN_SERVICE", token);
                } else if (newStatus == RequestStatus.COMPLETED) {
                    updateVehicleStatus(request.getVehicleId(), "ACTIVE", token);
                }
            } catch (Exception e) {
                log.error("Failed to sync vehicle status with Vehicle Service. Request ID: {}. Error: {}", id, e.getMessage());
                throw new DownstreamServiceException("Service unavailable. Failed to sync with Vehicle Service.", e);
            }

            return updatedRequest;
        });
    }

    public Optional<ServiceRequest> assignTechnician(Long id, String technician, String token) {
        return repository.findById(id).map(request -> {
            Map<String, Object> vehicle = getVehicle(request.getVehicleId(), token);
            ensureVehicleNotRetired(stringValue(vehicle.get("status")));
            
            ServiceRequest updatedRequest = transactionTemplate.execute(status -> {
                ServiceRequest req = repository.findById(id).orElseThrow();
                ensureMutable(req.getStatus());
                if (req.getStatus() != RequestStatus.APPROVED) {
                    throw new IllegalStateException("Technician assignment is only allowed for APPROVED requests.");
                }
                req.setAssignedTechnician(technician);
                req.setStatus(RequestStatus.ASSIGNED);
                return repository.save(req);
            });

            createMaintenanceTask(request.getVehicleId(), request.getRequestType().name(), request.getDescription(), technician, token);

            return updatedRequest;
        });
    }

    public Optional<ServiceRequest> completeRequest(Long id, String resolutionNotes, Double downtimeHours, String token) {
        return repository.findById(id).map(request -> {
            Map<String, Object> vehicle = getVehicle(request.getVehicleId(), token);
            ensureVehicleNotRetired(stringValue(vehicle.get("status")));
            
            ServiceRequest updatedRequest = transactionTemplate.execute(status -> {
                ServiceRequest req = repository.findById(id).orElseThrow();
                ensureMutable(req.getStatus());
                if (req.getStatus() != RequestStatus.IN_PROGRESS) {
                    throw new IllegalStateException("Only IN_PROGRESS requests can be completed.");
                }
                req.setResolutionNotes(resolutionNotes);
                req.setDowntimeHours(downtimeHours);
                req.setStatus(RequestStatus.COMPLETED);
                return repository.save(req);
            });

            updateVehicleStatus(request.getVehicleId(), "ACTIVE", token);
            return updatedRequest;
        });
    }

    private void updateVehicleStatus(Long vehicleId, String status, String token) {
        String url = vehicleServiceUrl + "/api/vehicles/" + vehicleId + "/status";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("Authorization", token);
        }

        HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("status", status), headers);

        int maxRetries = 3;
        for (int i = 0; i <= maxRetries; i++) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PATCH, request, String.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("Successfully updated vehicle {} status to {}", vehicleId, status);
                    return;
                }
            } catch (RestClientException e) {
                log.warn("Attempt {} failed to update vehicle status: {}", i + 1, e.getMessage());
                if (i == maxRetries) {
                    throw new DownstreamServiceException("Failed to update vehicle status in Vehicle Service after multiple retries.", e);
                }
                // Removed Thread.sleep() to fix lock contention; retry immediately or rely on async queue in production
            }
        }
    }

    private void createMaintenanceTask(Long vehicleId, String taskType, String description, String technician, String token) {
        if (maintenanceServiceUrl == null || maintenanceServiceUrl.isEmpty()) {
            log.warn("Maintenance service URL is not configured. Skipping task dispatch.");
            return;
        }

        String url = maintenanceServiceUrl + "/api/tasks/add?username=" + technician;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("Authorization", token);
        }

        Map<String, Object> payload = Map.of(
            "vehicleId", vehicleId,
            "taskType", taskType,
            "description", description != null ? description : ""
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully dispatched task for vehicle {} to technician {}'s queue", vehicleId, technician);
            } else {
                log.warn("Maintenance service returned non-2xx status code on task dispatch: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Failed to add task to maintenance queue for technician: {}. Error: {}", technician, e.getMessage());
        }
    }

    private void applyRequestTypeRules(ServiceRequest request, Map<String, Object> vehicle) {
        if (request.getRequestType() == null) {
            throw new IllegalArgumentException("Request type is required.");
        }
        
        LocalDate insuranceExpiry = localDateValue(vehicle.get("insuranceExpiry"));
        LocalDate nextServiceDate = localDateValue(vehicle.get("nextServiceDate"));
        Integer nextServiceMileage = intValue(vehicle.get("nextServiceMileage"));
        Integer currentMileage = intValue(vehicle.get("currentMileage"));
        LocalDate today = LocalDate.now();

        switch (request.getRequestType()) {
            case BREAKDOWN -> {
                request.setPriority(Priority.HIGH);
            }
            case INSURANCE_RENEWAL -> {
                if (insuranceExpiry == null) {
                    throw new IllegalStateException("Insurance expiry is required for INSURANCE_RENEWAL requests.");
                }
                boolean nearingExpiry = !insuranceExpiry.isAfter(today.plusDays(30));
                if (!nearingExpiry) {
                    throw new IllegalStateException("INSURANCE_RENEWAL is only allowed within 30 days of expiry.");
                }
            }
            case ROUTINE_SERVICE -> {
                boolean dueByDate = nextServiceDate != null && !nextServiceDate.isAfter(today);
                boolean dueByMileage = nextServiceMileage != null && currentMileage != null && currentMileage >= nextServiceMileage;
                if (!dueByDate && !dueByMileage) {
                    throw new IllegalStateException("ROUTINE_SERVICE is only allowed when service is due.");
                }
            }
            default -> {
            }
        }
    }

    private Map<String, Object> getVehicle(Long vehicleId, String token) {
        String url = vehicleServiceUrl + "/api/vehicles/" + vehicleId;
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("Authorization", token);
        }

        HttpEntity<Void> request = new HttpEntity<>(headers);
        ResponseEntity<Map<String, Object>> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientException e) {
            throw new DownstreamServiceException("Failed to fetch vehicle details from Vehicle Service.", e);
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Vehicle not found or unavailable.");
        }
        return response.getBody();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate localDateValue(Object value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private void ensureVehicleNotRetired(String vehicleStatus) {
        if ("RETIRED".equals(vehicleStatus)) {
            throw new IllegalStateException("Retired vehicles are blocked from maintenance request operations.");
        }
    }

    private void ensureMutable(RequestStatus status) {
        if (status == RequestStatus.COMPLETED || status == RequestStatus.REJECTED) {
            throw new IllegalStateException("Terminal requests cannot be modified.");
        }
    }

    private void ensureValidTransition(RequestStatus current, RequestStatus next) {
        boolean valid = switch (current) {
            case OPEN -> next == RequestStatus.PENDING_APPROVAL;
            case PENDING_APPROVAL -> next == RequestStatus.APPROVED || next == RequestStatus.REJECTED;
            case APPROVED -> next == RequestStatus.ASSIGNED;
            case ASSIGNED -> next == RequestStatus.IN_PROGRESS;
            case IN_PROGRESS -> next == RequestStatus.COMPLETED;
            case COMPLETED, REJECTED -> false;
        };
        if (!valid) {
            throw new IllegalStateException("Invalid status transition: " + current + " -> " + next);
        }
    }
}
