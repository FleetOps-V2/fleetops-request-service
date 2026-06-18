-- Additional service requests from driver2 and driver3
-- request_type must match RequestType enum: ROUTINE_SERVICE, BREAKDOWN, INSURANCE_RENEWAL, TIRE_CHANGE, OIL_CHANGE, BATTERY, OTHER
INSERT INTO service_requests (vehicle_id, vehicle_number, request_type, priority, description,
                               status, requested_by, created_at, updated_at)
VALUES
  (11, 'FL-011', 'OTHER',           'LOW',    'Fuel card not recognised at station 7',        'OPEN',             'driver2', NOW(), NOW()),
  (12, 'FL-012', 'OIL_CHANGE',      'HIGH',   'Oil change overdue — FL-012 at 48k km',        'OPEN',             'driver3', NOW(), NOW()),
  (14, 'FL-014', 'OTHER',           'MEDIUM', 'Suspension noise on FL-014, needs inspection', 'PENDING_APPROVAL', 'driver2', NOW(), NOW())
ON CONFLICT DO NOTHING;
