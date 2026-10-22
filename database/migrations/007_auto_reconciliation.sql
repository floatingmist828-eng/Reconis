SET XACT_ABORT ON;
BEGIN TRANSACTION;
IF COL_LENGTH('dbo.reconciliation_batch','acquisition_key') IS NULL
 ALTER TABLE dbo.reconciliation_batch ADD
 acquisition_key CHAR(64) NULL, acquisition_state VARCHAR(24) NULL,
 acquisition_env VARCHAR(20) NULL, acquisition_app VARCHAR(64) NULL,
 acquisition_attempts INT NOT NULL CONSTRAINT DF_batch_acquisition_attempts DEFAULT 0,
 acquisition_generation INT NOT NULL CONSTRAINT DF_batch_acquisition_generation DEFAULT 1,
 acquisition_token VARCHAR(36) NULL, acquisition_lease_until DATETIME2(3) NULL,
 acquisition_next_at DATETIME2(3) NULL, acquisition_message NVARCHAR(500) NULL,
 acquisition_format VARCHAR(8) NULL, acquisition_result NVARCHAR(MAX) NULL,
 acquisition_daily BIT NOT NULL CONSTRAINT DF_batch_acquisition_daily DEFAULT 0;
IF COL_LENGTH('dbo.reconciliation_batch','acquisition_owner') IS NULL
 ALTER TABLE dbo.reconciliation_batch ADD acquisition_owner BIGINT NULL REFERENCES dbo.app_user(id);
EXEC(N'UPDATE dbo.reconciliation_batch SET acquisition_owner=created_by WHERE source_type=''AUTO_JOB'' AND acquisition_owner IS NULL;');
IF EXISTS(SELECT 1 FROM sys.check_constraints WHERE name='CK_batch_source_type' AND parent_object_id=OBJECT_ID('dbo.reconciliation_batch'))
 ALTER TABLE dbo.reconciliation_batch DROP CONSTRAINT CK_batch_source_type;
ALTER TABLE dbo.reconciliation_batch ADD CONSTRAINT CK_batch_source_type
 CHECK(source_type IN ('XLSX','XML','ALIPAY_CSV','ALIPAY_ZIP','TRADE_QUERY','DEMO_SEED','AUTO_JOB'));
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.reconciliation_batch') AND name='UX_batch_acquisition_key')
 EXEC(N'CREATE UNIQUE INDEX UX_batch_acquisition_key ON dbo.reconciliation_batch(acquisition_key) WHERE acquisition_key IS NOT NULL;');
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.reconciliation_batch') AND name='IX_batch_acquisition_work')
 EXEC(N'CREATE INDEX IX_batch_acquisition_work ON dbo.reconciliation_batch(acquisition_env,acquisition_app,acquisition_state,acquisition_next_at)
 INCLUDE(acquisition_lease_until) WHERE source_type=''AUTO_JOB'';');
IF NOT EXISTS(SELECT 1 FROM sys.check_constraints WHERE name='CK_batch_acquisition' AND parent_object_id=OBJECT_ID('dbo.reconciliation_batch'))
 EXEC(N'ALTER TABLE dbo.reconciliation_batch ADD CONSTRAINT CK_batch_acquisition CHECK
 ((source_type<>''AUTO_JOB'' AND acquisition_key IS NULL) OR
  (source_type=''AUTO_JOB'' AND scope_type=''PLATFORM_UNASSIGNED'' AND merchant_id IS NULL AND status=''IMPORTING''
   AND acquisition_key IS NOT NULL AND acquisition_env IS NOT NULL AND acquisition_app IS NOT NULL
   AND acquisition_state IS NOT NULL AND acquisition_state IN (''QUEUED'',''FETCHING'',''VALIDATING'',''RECONCILING'',''RETRY_WAIT'',''SUCCEEDED'',''FAILED'',''UNCONFIGURED'',''UNSUPPORTED'',''TEMPLATE_ONLY'')
   AND acquisition_attempts BETWEEN 0 AND 4 AND acquisition_generation>0));');
COMMIT;
GO
