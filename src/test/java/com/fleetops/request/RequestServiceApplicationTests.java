package com.fleetops.request;

import com.fleetops.request.entity.ServiceRequest;
import com.fleetops.request.entity.ServiceRequest.Priority;
import com.fleetops.request.entity.ServiceRequest.RequestStatus;
import com.fleetops.request.entity.ServiceRequest.RequestType;
import com.fleetops.request.repository.ServiceRequestRepository;
import com.fleetops.request.service.ServiceRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
		"app.vehicle-service-url=http://vehicle-service",
		"spring.sql.init.mode=never"
})
class RequestServiceApplicationTests {

	@Autowired
	private ServiceRequestService service;

	@Autowired
	private ServiceRequestRepository repository;

	@MockitoBean
	private RestTemplate restTemplate;

	private static final String TOKEN = "Bearer test-token";

	@Test
	void contextLoads() {
	}

	@Test
	void enforcesStrictStateMachineAndTerminalImmutability() {
		repository.deleteAll();
		mockVehicle(1L, "ACTIVE", "driver1", LocalDate.now().minusDays(1), 9000, 9500, LocalDate.now().plusDays(10));

		ServiceRequest request = new ServiceRequest();
		request.setVehicleId(1L);
		request.setVehicleNumber("V-001");
		request.setRequestType(RequestType.ROUTINE_SERVICE);
		request.setPriority(Priority.MEDIUM);
		request.setDescription("Routine");
		ServiceRequest created = service.createRequest(request, "driver1", TOKEN, true);

		assertThrows(IllegalStateException.class,
				() -> service.updateRequestStatus(created.getId(), RequestStatus.COMPLETED, "manager1", TOKEN));

		service.updateRequestStatus(created.getId(), RequestStatus.PENDING_APPROVAL, "manager1", TOKEN);
		service.updateRequestStatus(created.getId(), RequestStatus.APPROVED, "manager1", TOKEN);
		service.assignTechnician(created.getId(), "tech1", TOKEN);
		service.updateRequestStatus(created.getId(), RequestStatus.IN_PROGRESS, "manager1", TOKEN);
		service.completeRequest(created.getId(), "done", 1.5, TOKEN);

		ServiceRequest done = repository.findById(created.getId()).orElseThrow();
		assertEquals(RequestStatus.COMPLETED, done.getStatus());
		assertThrows(IllegalStateException.class,
				() -> service.assignTechnician(done.getId(), "tech2", TOKEN));
	}

	@Test
	void blocksDuplicateActiveRequestsForSameVehicleUnderConcurrentCreate() throws Exception {
		repository.deleteAll();
		mockVehicle(2L, "ACTIVE", "driver2", LocalDate.now().minusDays(1), 10000, 12000, LocalDate.now().plusDays(8));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch latch = new CountDownLatch(1);

		Future<Boolean> f1 = executor.submit(() -> createAfterLatch(latch, 2L, "driver2"));
		Future<Boolean> f2 = executor.submit(() -> createAfterLatch(latch, 2L, "driver2"));
		latch.countDown();

		boolean r1 = f1.get();
		boolean r2 = f2.get();
		executor.shutdownNow();

		assertEquals(1, repository.findAll().size(), "Only one active request should persist");
		assertNotEquals(r1, r2, "Exactly one concurrent request should succeed");
	}

	@Test
	void enforcesRequestTypeRulesAndRetiredBlocking() {
		repository.deleteAll();
		mockVehicle(3L, "RETIRED", "driver3", LocalDate.now().minusDays(1), 12000, 13000, LocalDate.now().plusDays(40));
		ServiceRequest retiredRequest = new ServiceRequest();
		retiredRequest.setVehicleId(3L);
		retiredRequest.setRequestType(RequestType.ROUTINE_SERVICE);
		retiredRequest.setPriority(Priority.MEDIUM);
		retiredRequest.setDescription("Retired attempt");
		assertThrows(IllegalStateException.class, () -> service.createRequest(retiredRequest, "driver3", TOKEN, true));

		mockVehicle(4L, "ACTIVE", "driver4", LocalDate.now().plusDays(90), 1000, 5000, LocalDate.now().plusDays(60));
		ServiceRequest insuranceTooEarly = new ServiceRequest();
		insuranceTooEarly.setVehicleId(4L);
		insuranceTooEarly.setRequestType(RequestType.INSURANCE_RENEWAL);
		insuranceTooEarly.setDescription("Too early");
		assertThrows(IllegalStateException.class, () -> service.createRequest(insuranceTooEarly, "driver4", TOKEN, true));

		mockVehicle(5L, "ACTIVE", "driver5", LocalDate.now().plusDays(90), 1000, 5000, LocalDate.now().plusDays(5));
		ServiceRequest breakdown = new ServiceRequest();
		breakdown.setVehicleId(5L);
		breakdown.setRequestType(RequestType.BREAKDOWN);
		breakdown.setPriority(Priority.LOW);
		breakdown.setDescription("Engine failure");
		ServiceRequest created = service.createRequest(breakdown, "driver5", TOKEN, true);
		assertEquals(Priority.HIGH, created.getPriority());
	}

	private boolean createAfterLatch(CountDownLatch latch, Long vehicleId, String requestedBy) {
		try {
			latch.await();
			ServiceRequest request = new ServiceRequest();
			request.setVehicleId(vehicleId);
			request.setVehicleNumber("V-" + vehicleId);
			request.setRequestType(RequestType.ROUTINE_SERVICE);
			request.setPriority(Priority.MEDIUM);
			request.setDescription("Concurrent create");
			service.createRequest(request, requestedBy, TOKEN, true);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void mockVehicle(Long id,
							 String status,
							 String assignedDriverId,
							 LocalDate nextServiceDate,
							 Integer currentMileage,
							 Integer nextServiceMileage,
							 LocalDate insuranceExpiry) {
		Map<String, Object> vehicle = Map.of(
				"id", id,
				"status", status,
				"assignedDriverId", assignedDriverId,
				"nextServiceDate", nextServiceDate.toString(),
				"currentMileage", currentMileage,
				"nextServiceMileage", nextServiceMileage,
				"insuranceExpiry", insuranceExpiry.toString()
		);

		when(restTemplate.exchange(contains("/api/vehicles/" + id), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
				.thenReturn(new ResponseEntity(vehicle, HttpStatus.OK));

		when(restTemplate.exchange(contains("/api/vehicles/" + id + "/status"), eq(HttpMethod.PATCH), any(HttpEntity.class), eq(String.class)))
				.thenReturn(new ResponseEntity<>("ok", HttpStatus.OK));
	}
}


