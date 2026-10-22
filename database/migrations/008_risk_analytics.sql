-- Reconstruct case status at a historical cutoff without scanning unrelated access logs.
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.audit_log') AND name=N'IX_audit_case_snapshot')
 CREATE INDEX IX_audit_case_snapshot ON dbo.audit_log(object_id,occurred_at DESC,id DESC)
 INCLUDE(after_json) WHERE object_type='CASE' AND event_category='DB_CHANGE';
GO
