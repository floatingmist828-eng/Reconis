-- Persist safe payment feedback and QR codes across page reloads. A precreate lease
-- keeps concurrent requests from issuing the same channel request simultaneously.
IF COL_LENGTH('dbo.payment_attempt','qr_code') IS NULL
 ALTER TABLE dbo.payment_attempt ADD qr_code NVARCHAR(2048) NULL;
IF COL_LENGTH('dbo.payment_attempt','last_result_code') IS NULL
 ALTER TABLE dbo.payment_attempt ADD last_result_code VARCHAR(64) NULL;
IF COL_LENGTH('dbo.payment_attempt','last_result_message') IS NULL
 ALTER TABLE dbo.payment_attempt ADD last_result_message NVARCHAR(300) NULL;
IF COL_LENGTH('dbo.payment_attempt','next_action') IS NULL
 ALTER TABLE dbo.payment_attempt ADD next_action VARCHAR(32) NULL;
IF COL_LENGTH('dbo.payment_attempt','precreate_count') IS NULL
 ALTER TABLE dbo.payment_attempt ADD precreate_count INT NOT NULL CONSTRAINT DF_payment_precreate_count DEFAULT 0;
IF COL_LENGTH('dbo.payment_attempt','dispatch_token') IS NULL
 ALTER TABLE dbo.payment_attempt ADD dispatch_token VARCHAR(36) NULL;
IF COL_LENGTH('dbo.payment_attempt','dispatch_lease_until') IS NULL
 ALTER TABLE dbo.payment_attempt ADD dispatch_lease_until DATETIME2(3) NULL;
GO
-- Existing active attempts may already have reached Alipay before this upgrade.
-- They must query the original request before retrying, never be treated as a first send.
UPDATE dbo.payment_attempt SET precreate_count=1
 WHERE precreate_count=0 AND status IN ('CREATED','PENDING','UNKNOWN');
GO
