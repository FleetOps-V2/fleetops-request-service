ALTER TABLE service_requests
    ADD COLUMN IF NOT EXISTS step_functions_execution_arn VARCHAR(500);
