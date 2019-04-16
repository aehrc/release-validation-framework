/********************************************************************************
	file-centric-snapshot-module-dependency-moduleid-is-valid-module.sql

	Assertion:
	ModuleId is set to a valid, active module in Module Dependency Snapshot file

********************************************************************************/

call validateModuleIdIsValidModuleInModuleDependencySnapshot_proc(<RUNID>,'<ASSERTIONUUID>',
        'moduledependencyrefset_s','moduleid ', ' is not valid descendant of 900000000000443000 |Module|');

	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Module with id = ',a.id,' in Module Dependency Snapshot file is inactive')
    from curr_moduledependencyrefset_s a where a.active = 0;
	commit;
