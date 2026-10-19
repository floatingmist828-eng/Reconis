-- 由 initialize.ps1 在独立沙箱库中调用，密码散列经 stdin 生成，不在仓库保存默认密码。
IF DB_NAME()<>'ReconisSandbox' THROW 51110,N'本脚本仅用于沙箱账号初始化',1;
INSERT app_user(username,password_hash,display_name,platform_role,status) VALUES('admin','$(AdminPasswordHash)',N'沙箱管理员','ADMIN','ACTIVE');
GO
