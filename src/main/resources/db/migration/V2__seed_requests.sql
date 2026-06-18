-- Seed demo service requests
-- request_type must match RequestType enum: ROUTINE_SERVICE, BREAKDOWN, INSURANCE_RENEWAL, TIRE_CHANGE, OIL_CHANGE, BATTERY, OTHER
INSERT INTO service_requests (vehicle_id, vehicle_number, request_type, priority, description,
                               status, requested_by, created_at, updated_at)
VALUES
  (6,  'FL-006', 'OIL_CHANGE',      'HIGH',   'Engine oil overdue — vehicle pulled off route', 'OPEN',             'driver1',  NOW(), NOW()),
  (1,  'FL-001', 'ROUTINE_SERVICE', 'MEDIUM', 'Scheduled 6-month service',                    'PENDING_APPROVAL', 'driver1',  NOW(), NOW()),
  (9,  'FL-009', 'OTHER',           'LOW',    'AC not cooling — secondary vehicle',            'OPEN',             'manager1', NOW(), NOW())
ON CONFLICT DO NOTHING;
