ALTER TABLE reconciliation_runs ADD upper_order_id BIGINT NOT NULL DEFAULT 0;
UPDATE reconciliation_runs r
 SET upper_order_id=(SELECT COALESCE(MAX(o.id),0) FROM fulfillment_orders o WHERE o.campaign_id=r.campaign_id);
CREATE INDEX ix_order_campaign_id ON fulfillment_orders(campaign_id,id);
