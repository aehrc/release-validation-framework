
/********************************************************************************
	file-centric-snapshot-correct-module-id.sql

	Assertion:
	The module id of all data must be correct

********************************************************************************/
drop table if exists module_id;
create table module_id(
moduleid bigint(20) not null
) engine=myisam default charset=utf8;

insert into module_id(moduleid) values(900000000000012004); /* SNOMED CT model component module */

insert into module_id
select distinct moduleid from curr_moduledependencyrefset_s
where active = 1;

call validate_module_id('<PROSPECTIVE>',<RUNID>,'<ASSERTIONUUID>');

/* AU PATCH (assertions-au, ruling: missing active filter, minimal variant).
   The moduledependencyrefset statement - and ONLY that one - must not report
   INACTIVE rows: a retired MDRS row is how RF2 records that a dependency was
   withdrawn, so its module is by definition no longer declared, and a snapshot
   has to keep it. That statement is generated inside validate_module_id (one
   per *_s table, resource/file-centric-moduleid-validation-proc.sql), so there
   is no WHERE clause here to add `active = 1` to. Removing exactly the rows it
   reported for inactive members is the same predicate: the details text is the
   procedure's own, and a snapshot holds one row per id. Every other table, and
   active MDRS rows, are reported exactly as before. */
delete from qa_result
where run_id = <RUNID>
	and assertion_id = '<ASSERTIONUUID>'
	and details in (
		select concat('moduledependencyrefset', ' ::id= ', id, ' ::module id: ', moduleid, ' is not in module dependency list')
		from curr_moduledependencyrefset_s
		where active = '0');
