-- case management --

ALTER TABLE ACT_RU_CASE_EXECUTION
  ADD SUPER_EXEC_ NVARCHAR2(64);

ALTER TABLE ACT_RU_CASE_EXECUTION
  ADD REQUIRED_ NUMBER(1,0) CHECK (REQUIRED_ IN (1,0));

-- history --

ALTER TABLE ACT_HI_ACTINST
  ADD CALL_CASE_INST_ID_ NVARCHAR2(64);

ALTER TABLE ACT_HI_PROCINST
  ADD SUPER_CASE_INSTANCE_ID_ NVARCHAR2(64);

ALTER TABLE ACT_HI_CASEINST
  ADD SUPER_PROCESS_INSTANCE_ID_ NVARCHAR2(64);

ALTER TABLE ACT_HI_CASEACTINST
  ADD REQUIRED_ NUMBER(1,0) CHECK (REQUIRED_ IN (1,0));
