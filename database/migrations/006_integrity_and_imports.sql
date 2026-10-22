SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH('dbo.refund_request','dispatch_token') IS NULL
 ALTER TABLE dbo.refund_request ADD dispatch_token VARCHAR(36) NULL;
IF COL_LENGTH('dbo.refund_request','dispatch_lease_until') IS NULL
 ALTER TABLE dbo.refund_request ADD dispatch_lease_until DATETIME2(3) NULL;
IF COL_LENGTH('dbo.reconciliation_batch','ingest_options_sha256') IS NULL
 ALTER TABLE dbo.reconciliation_batch ADD ingest_options_sha256 CHAR(64) NULL;

DECLARE @constraint SYSNAME;
DECLARE @dropSql NVARCHAR(MAX);
DECLARE source_checks CURSOR LOCAL FAST_FORWARD FOR
 SELECT name FROM sys.check_constraints
 WHERE parent_object_id=OBJECT_ID('dbo.reconciliation_batch')
 AND definition LIKE '%source_type%';
OPEN source_checks;
FETCH NEXT FROM source_checks INTO @constraint;
WHILE @@FETCH_STATUS=0
BEGIN
 SET @dropSql=N'ALTER TABLE dbo.reconciliation_batch DROP CONSTRAINT '+QUOTENAME(@constraint);
 EXEC(@dropSql);
 FETCH NEXT FROM source_checks INTO @constraint;
END;
CLOSE source_checks;
DEALLOCATE source_checks;
ALTER TABLE dbo.reconciliation_batch WITH CHECK ADD CONSTRAINT CK_batch_source_type
 CHECK(source_type IN ('XLSX','XML','ALIPAY_CSV','ALIPAY_ZIP','TRADE_QUERY','DEMO_SEED','AUTO_JOB'));
IF EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.reconciliation_batch') AND name='UX_import_file')
 DROP INDEX UX_import_file ON dbo.reconciliation_batch;
-- Dynamic compilation sees the column just added in this batch.
EXEC(N'CREATE UNIQUE INDEX UX_import_file ON dbo.reconciliation_batch(scope_type,merchant_id,file_sha256,ingest_options_sha256)
 WHERE file_sha256 IS NOT NULL AND statement_source_batch_id IS NULL;');
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.reconciliation_result') AND name='IX_result_event_observation')
 CREATE INDEX IX_result_event_observation ON dbo.reconciliation_result(source_event_key,detected_at) INCLUDE(batch_id,classification);
COMMIT;
GO
