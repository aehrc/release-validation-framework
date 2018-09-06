/********************************************************************************
	release-type-full-snapshot-refset-description-type-identical-to-previous-release

	Assertion:
	- Verify that DescriptionType Full and Snapshot files should be identical to last release

********************************************************************************/
insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		'',
		concat('Warning: DescriptionType:',a.id,' is not found in last release DescriptionType Snapshot file')
	from curr_descriptiontype_s a
	where not exists (select * from prev_descriptiontype_s);
	commit;

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		'',
		concat('Warning: DescriptionType:',a.id,' is not found in last release DescriptionType Full file')
	from curr_descriptiontype_f a
	where not exists (select * from prev_descriptiontype_f);
	commit;