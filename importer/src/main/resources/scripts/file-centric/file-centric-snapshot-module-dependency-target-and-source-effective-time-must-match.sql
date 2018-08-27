/********************************************************************************
	file-centric-snapshot-module-dependency-target-and-source-effective-time-must-match

	Assertion:
	For each record in Module Dependency Snapshot file, the targetEffectiveTime always matches the sourceEffectiveTime
	(International Edition or all Derivative Packages)

	For each record in Module Dependency Snapshot file, the effectiveTime always matches the sourceEffectiveTime and targetEffectiveTime
	(International Edition or all Derivative Packages)

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset with id = ',a.id, ' in Module Dependency Snapshot file , targetEffectiveTime = ',a.targeteffectivetime,' does not match the sourceEffectiveTime = ',a.sourceeffectivetime)
	from curr_moduledependencyrefset_s a
	where a.targeteffectivetime <> a.sourceeffectivetime;
	commit;

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset with id = ',a.id, ' in Module Dependency Snapshot file , effectiveTime = ',a.effectivetime,' does not match the sourceEffectiveTime = ', a.sourceeffectivetime,' or targetEffectiveTime = ',a.targeteffectivetime)
	from curr_moduledependencyrefset_s a
	where a.effectivetime <> a.sourceeffectivetime
    or a.effectivetime <> a.targeteffectivetime;
	commit;