/********************************************************************************
	release-type-delta-no-reactivated-concept

	Assertion:
	Verify that there are no concepts that have been re-activated since the last release

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.id,
		concat('Warning: Concept:',a.id,' has been reactivated since the last release')
	from curr_concept_d a
	where a.active = 1 and exists (select id from prev_concept_s where active = 0 and id = a.id);
	commit;