ALTER TABLE loan_return
    ADD COLUMN margin_call_id BIGINT REFERENCES margin_recall(id);

CREATE INDEX idx_lender_return_margin_call
    ON loan_return (margin_call_id, status);
