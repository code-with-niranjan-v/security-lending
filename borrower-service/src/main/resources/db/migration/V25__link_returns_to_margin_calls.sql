ALTER TABLE loan_return
    ADD COLUMN margin_call_id BIGINT REFERENCES margin_recall(id);

CREATE INDEX idx_loan_return_margin_call_status
    ON loan_return (margin_call_id, status);
