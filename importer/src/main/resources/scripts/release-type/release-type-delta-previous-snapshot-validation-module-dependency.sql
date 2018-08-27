/********************************************************************************
	component-centric-delta-module-dependency-id-is-same-for-the-same-moduledependency

	Assertion:
	For each record in Module Dependency Delta file, the UUID is same for the same record
	identified by the index of the three fields moduleid + refsetid + referencedcomponentid

********************************************************************************/

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Module Dependency with id = ',a.id,' in previous Snapshot does not exist in current Delta file')
    from (
    select distinct id,moduleid from prev_moduledependencyrefset_s t1
    where t1.id not in (select id from curr_moduledependencyrefset_s)
    ) a;
	commit;

  insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Module Dependency with id = ',a.id,' in Delta file has different ID with Module Dependency ',a.previous_id,
		 ' which share the same refsetId = ', a.refsetid,',moduleId = ', a.moduleid, ' referencedComponentId = ', a.referencedcomponentid)
    from (
    select distinct t1.id,t1.moduleId,t1.refsetid,t1.referencedcomponentid,t2.id as previous_id
    from curr_moduledependencyrefset_d t1
    join prev_moduledependencyrefset_s t2
    on t1.moduleid = t2.moduleid
    and t1.refsetid = t2.refsetid
    and t1.referencedcomponentid = t2.referencedcomponentid
    where t1.id<>t2.id
    ) a;
	commit;

  insert into qa_result (runid, assertionuuid, concept_id, details)
    select
    	<RUNID>,
    	'<ASSERTIONUUID>',
    	a.referencedcomponentid,
    	concat('Module Dependency id = ',a.referencedcomponentid, ' is the updated record for pre-existing active records but effectiveTime, sourceEffectiveTime and targetEffectiveTime is not set to current release effectiveTime')
    from curr_moduledependencyrefset_s a, prev_moduledependencyrefset_s b
    where a.id = b.id
    and a.moduleid = b.moduleid
    and a.refsetid = b.refsetid
    and a.referencedcomponentid = b.referencedcomponentid
    and a.active = 1
    and a.targeteffectivetime <> a.effectivetime
    and a.sourceeffectivetime <> a.effectivetime
    and a.effectivetime <> '<CURR_EFFECTIVE_TIME>'
    and '' <> '<CURR_EFFECTIVE_TIME>';
  commit;