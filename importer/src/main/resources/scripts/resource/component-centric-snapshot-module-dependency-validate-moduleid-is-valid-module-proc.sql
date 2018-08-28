
/********************************************************************************
	component-centric-snapshot-module-dependency-validate-moduleid-is-valid-module-proc.sql

	Defines a procedure to validate whether a moduleId is a descendant of valid module in in Module Dependency Snapshot file

********************************************************************************/

drop procedure if exists validateModuleIdIsValidModuleInModuleDependencySnapshot_proc;
create procedure validateModuleIdIsValidModuleInModuleDependencySnapshot_proc(runId bigint, assertionId varchar(36), tableName varchar(255), columnName varchar(255), expression varchar(4000))
begin
declare currentDepth integer default 0;
declare parentsCount integer;
drop table if exists temp_snapshot_concept_hierachy_tree_md;
create table temp_snapshot_concept_hierachy_tree_md(
    conceptId bigint(20) not null,
    parentId bigint(20) not null,
    depth integer
);

set @runSql = concat("insert into temp_snapshot_concept_hierachy_tree_md(conceptId, parentId, depth)
select sourceid, destinationid,", currentDepth ," from stated_relationship_s s
where s.active = 1 and s.typeid = 116680003 and s.destinationid in (900000000000443000);");
prepare statement from @runSql;
execute statement;
set parentsCount = (select count(distinct conceptId) from temp_snapshot_concept_hierachy_tree_md where depth = currentDepth);
while parentsCount > 0 do
insert into temp_snapshot_concept_hierachy_tree_md(conceptId, parentId, depth)
select sourceId, destinationid, (currentDepth + 1) from stated_relationship_s s
where s.active = 1 and s.typeid = 116680003 and s.destinationid in (select distinct conceptId from temp_snapshot_concept_hierachy_tree_md where depth = currentDepth);

set currentDepth = currentDepth + 1;

set parentsCount = (select count(distinct conceptId) from temp_snapshot_concept_hierachy_tree_md where depth = currentDepth);

end while;

drop table if exists temp_snapshot_refset_conceptid_md;
create table temp_snapshot_refset_conceptid_md(id varchar(36),conceptId bigint(20));
set @runSql = concat("insert into temp_snapshot_refset_conceptid_md(id,conceptId) select id, ", columnName, " from ", tableName, ";");
prepare statement from @runSql;
execute statement;

insert into qa_result (run_id, assertion_id,concept_id, details)
select
	runId,
	assertionId,
	result.conceptId,
	concat("Refset with Id = ",result.id, " referenced in the column: ", columnName, "of table: ", tableName, expression)
	from  (select id, conceptId from temp_snapshot_refset_conceptid_md where conceptId not in (select conceptId from temp_snapshot_concept_hierachy_tree_md)) as result;

drop table if exists temp_snapshot_concept_hierachy_tree_md;
drop table if exists temp_snapshot_refset_conceptid_md;
end;