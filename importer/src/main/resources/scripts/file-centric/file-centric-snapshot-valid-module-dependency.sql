/********************************************************************************
  file-centric-snapshot-valid-module-dependency.sql

  Assertion:
  Verify that in the ModuleDependency files the refsetId field is always set to “900000000000534007”

 ********************************************************************************/

	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset: id=',a.id,' in MODULE DEPENDENCY SNAPSHOT is set to ',a.refsetid,' instead of 900000000000534007')
	from curr_moduledependencyrefset_s a
	where a.refsetid <> '900000000000534007';
	commit;