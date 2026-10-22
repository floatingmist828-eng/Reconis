-- The order-item freeze trigger must inspect every payment attempt, including non-primary ones.
-- UX_payment_primary is filtered by is_primary=1 and cannot support that check on its own.
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.payment_attempt') AND name=N'IX_payment_order')
 CREATE INDEX IX_payment_order ON dbo.payment_attempt(order_id) INCLUDE(status,is_primary);
GO
