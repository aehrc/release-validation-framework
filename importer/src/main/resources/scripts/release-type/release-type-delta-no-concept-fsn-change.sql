/********************************************************************************
	release-type-delta-no-concept-fsn-change

	Assertion:
	- Verify that there are no active concepts with changed FSNs
	- Verify that there are no concepts with FSNs with changed semantic tags

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.conceptid,
		concat('Warning: Description:',a.id,' is a changed FSN')
	from curr_description_d a where typeid = '900000000000003001' and a.active = 1
  and exists (select * from prev_description_s
  where active = 1 and typeid = '900000000000003001'
  and id = a.id
  and term <> a.term);
  commit;

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.conceptid,
		concat('Warning: Description:',a.id,' has semantic tag changed')
	from curr_description_d a where typeid = '900000000000003001'
  and exists (select * from prev_description_s
  where typeid = '900000000000003001'
  and id = a.id
  and concat('(',(substring_index(term,'(',-1))) <> concat('(',(substring_index(a.term,'(',-1))));
  commit;