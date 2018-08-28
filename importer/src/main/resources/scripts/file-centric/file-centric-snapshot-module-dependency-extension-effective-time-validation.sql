/********************************************************************************
	file-centric-snapshot-module-dependency-extension-effective-time-validation.sql

	Assertion:
	- Verify that in the ModuleDependency files for all Extension packages (DK, SE, etc), effectiveTime always matches the sourceEffectiveTime for each record
	- Verify that in the ModuleDependency files for all Extension packages (DK, SE, etc), the sourceEffectiveTime for each record is set to the effectiveTime for the Extension Package release
	- Verify that in the ModuleDependency files for all Extension packages (DK, SE, etc), the sourceEffectiveTime for each record is set to the effectiveTime for the Extension Package release, and the targetEffectiveTime is set to the effectiveTime for the dependent International Edition.

********************************************************************************/

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset with id = ',a.id, ' in Module Dependency Snapshot file , effectiveTime = ',effectivetime,' does not match the current release effective time = <CURR_EFFECTIVE_TIME>')
	from original_curr_moduledependencyrefset_s a
	where a.effectivetime <> '<CURR_EFFECTIVE_TIME>'
	and '' <> '<CURR_EFFECTIVE_TIME>';
	commit;

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset with id = ',a.id, ' in Module Dependency Snapshot file , targetEffectiveTime = ',targeteffectivetime,' does not match the dependency release effective time = <DEPENDENCY_EFFECTIVE_TIME>')
	from original_curr_moduledependencyrefset_s a
	where a.targeteffectivetime <> '<DEPENDENCY_EFFECTIVE_TIME>'
	and '' <> '<DEPENDENCY_EFFECTIVE_TIME>';
	commit;

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset with id = ',a.id, ' in Module Dependency Snapshot file , effectiveTime = ',effectivetime,' does not match the sourceEffectiveTime = ',a.sourceeffectivetime)
	from original_curr_moduledependencyrefset_s a
	where a.effectivetime <> a.sourceeffectivetime;
	commit;