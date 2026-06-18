-- Additional service requests from driver2 and driver3
INSERT INTO service_requests (vehicle_id, vehicle_number, request_type, priority, description,
                               status, requested_by, created_at, updated_at)
VALUES
  (11, 'FL-011', 'FUEL',        'LOW',    'Fuel card not recognised at station 7',         'OPEN',             'driver2', NOW(), NOW()),
  (12, 'FL-012', 'MAINTENANCE', 'HIGH',   'Oil change overdue — FL-012 at 48k km',         'OPEN',             'driver3', NOW(), NOW()),
  (14, 'FL-014', 'REPAIR',      'MEDIUM', 'Suspension noise on FL-014, needs inspection',  'PENDING_APPROVAL', 'driver2', NOW(), NOW())
ON CONFLICT DO NOTHING;
