/********************************************************************************
	release-type-delta-concept-definition-status-unchanged

	Assertion:
	- Verify that there are no concepts that changed from fully defined to primitive
	- Verify that there are no concepts that changed from primitive to fully defined

********************************************************************************/
insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.id,
		concat('Warning: Concept:',a.id,' has been changed from fully defined to primitive')
	from curr_concept_d a
	where a.definitionstatusid = 900000000000074008 and a.active = 1
	and exists (select id from prev_concept_s where id = a.id and definitionstatusid = 900000000000073002);
	commit;


insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.id,
		concat('Warning: Concept:',a.id,' has been changed from primitive to fully defined')
	from curr_concept_d a
	where a.definitionstatusid = 900000000000073002 and a.active = 1
	and exists (select id from prev_concept_s where id = a.id and definitionstatusid = 900000000000074008);
	commit;