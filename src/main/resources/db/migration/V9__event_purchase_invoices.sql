CREATE TABLE IF NOT EXISTS event_purchase_invoices (
    id UUID PRIMARY KEY,
    purchase_id UUID NOT NULL REFERENCES event_purchases(id) ON DELETE CASCADE,
    file_url TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO event_purchase_invoices (id, purchase_id, file_url, created_at)
SELECT uuid_generate_v4(), id, invoice_file_url, COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
FROM event_purchases
WHERE invoice_file_url IS NOT NULL
  AND invoice_file_url <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM event_purchase_invoices existing
      WHERE existing.purchase_id = event_purchases.id
        AND existing.file_url = event_purchases.invoice_file_url
  );

CREATE INDEX IF NOT EXISTS idx_event_purchase_invoices_purchase_id
    ON event_purchase_invoices(purchase_id);
