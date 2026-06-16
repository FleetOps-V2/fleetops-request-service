INSERT INTO service_requests (vehicle_id, vehicle_number, request_type, priority, description, status, requested_by, approved_by, assigned_technician, resolution_notes, downtime_hours, version) VALUES
(10, 'KL16ST5678', 'ROUTINE_SERVICE', 'MEDIUM', 'Routine 50,000 km general maintenance and multi-point inspection.', 'IN_PROGRESS', 'manager1', 'admin1', 'driver10', NULL, NULL, 0),
(11, 'KL17UV9012', 'OIL_CHANGE',      'LOW',    'Engine oil and filter replacement requested by driver.',       'OPEN',        'driver11', NULL,     NULL,       NULL, NULL, 0),
(12, 'KL18WX3456', 'BREAKDOWN',       'HIGH',   'Engine overheating alert near Highway 45. Needs towing.',      'ASSIGNED',    'driver12', 'admin1',  'driver12', NULL, NULL, 0);
