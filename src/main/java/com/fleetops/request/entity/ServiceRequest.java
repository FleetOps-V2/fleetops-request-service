package com.fleetops.request.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * ServiceRequest entity â€” the core workflow object of FleetOps.
 *
 * Status lifecycle (strict state machine):
 *   OPEN -> PENDING_APPROVAL -> APPROVED -> ASSIGNED -> IN_PROGRESS -> COMPLETED
 *                                                                    -> REJECTED (from any state before COMPLETED)
 *
 * Side effects (managed by ServiceRequestService via Vehicle Service HTTP call):
 *   IN_PROGRESS: vehicle.status = IN_SERVICE
 *   COMPLETED:   vehicle.status = ACTIVE
 *   BREAKDOWN request type automatically triggers OPEN status and sets vehicle to BREAKDOWN
 *
 * Duplicate protection:
 *   If vehicle.status == IN_SERVICE or BREAKDOWN, new requests are rejected with HTTP 409.
 */
@Entity
@Table(name = "service_requests")
public class ServiceRequest {

    public enum RequestStatus {
        OPEN, PENDING_APPROVAL, APPROVED, ASSIGNED, IN_PROGRESS, COMPLETED, REJECTED
    }

    public enum RequestType {
        ROUTINE_SERVICE, BREAKDOWN, INSURANCE_RENEWAL, TIRE_CHANGE, OIL_CHANGE, BATTERY, OTHER
    }

    public enum Priority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "vehicle_number", length = 20)
    private String vehicleNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 30)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Priority priority = Priority.MEDIUM;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestStatus status = RequestStatus.OPEN;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "assigned_technician", length = 100)
    private String assignedTechnician;

    @Column(name = "step_functions_execution_arn", length = 500)
    private String stepFunctionsExecutionArn;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "downtime_hours")
    private Double downtimeHours;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }

    public String getVehicleNumber() { return vehicleNumber; }
    public void setVehicleNumber(String vehicleNumber) { this.vehicleNumber = vehicleNumber; }

    public RequestType getRequestType() { return requestType; }
    public void setRequestType(RequestType requestType) { this.requestType = requestType; }

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public String getAssignedTechnician() { return assignedTechnician; }
    public void setAssignedTechnician(String assignedTechnician) { this.assignedTechnician = assignedTechnician; }

    public String getStepFunctionsExecutionArn() { return stepFunctionsExecutionArn; }
    public void setStepFunctionsExecutionArn(String stepFunctionsExecutionArn) { this.stepFunctionsExecutionArn = stepFunctionsExecutionArn; }

    public String getResolutionNotes() { return resolutionNotes; }
    public void setResolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; }

    public Double getDowntimeHours() { return downtimeHours; }
    public void setDowntimeHours(Double downtimeHours) { this.downtimeHours = downtimeHours; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}

