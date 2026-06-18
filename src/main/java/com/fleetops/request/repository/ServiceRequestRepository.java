package com.fleetops.request.repository;

import com.fleetops.request.entity.ServiceRequest;
import com.fleetops.request.entity.ServiceRequest.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long> {

    List<ServiceRequest> findByRequestedByOrderByCreatedAtDesc(String requestedBy);

    List<ServiceRequest> findByVehicleIdOrderByCreatedAtDesc(Long vehicleId);

    List<ServiceRequest> findByStatusOrderByCreatedAtDesc(RequestStatus status);

    List<ServiceRequest> findAllByOrderByCreatedAtDesc();

    long countByStatus(RequestStatus status);

    boolean existsByVehicleIdAndStatusIn(Long vehicleId, List<RequestStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sr FROM ServiceRequest sr WHERE sr.vehicleId = :vehicleId AND sr.status IN :statuses")
    List<ServiceRequest> findActiveRequestsForUpdate(@Param("vehicleId") Long vehicleId,
                                                     @Param("statuses") List<RequestStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sr FROM ServiceRequest sr WHERE sr.id = :id")
    Optional<ServiceRequest> findByIdForUpdate(@Param("id") Long id);
}

