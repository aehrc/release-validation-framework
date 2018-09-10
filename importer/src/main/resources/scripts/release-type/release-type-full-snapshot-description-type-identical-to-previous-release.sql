/********************************************************************************
	release-type-full-snapshot-refset-description-type-identical-to-previous-release

	Assertion:
	- Verify that DescriptionType Full and Snapshot files should be identical to last release

********************************************************************************/
insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		0,
		concat('Warning: DescriptionType:',a.id,' is not found in last release DescriptionType Snapshot file')
	from curr_descriptiontype_s a
	where not exists (select * from prev_descriptiontype_s where id = a.id and active= a.active and effectivetime = a.effectivetime and moduleid = a.moduleid and refsetid=a.refsetid and referencedcomponentid = a.referencedcomponentid
and descriptionformat = a.descriptionformat and descriptionlength = a.descriptionlength);
	commit;

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		0,
		concat('Warning: DescriptionType:',a.id,' is not found in last release DescriptionType Full file')
	from curr_descriptiontype_f a
	where not exists (select * from prev_descriptiontype_f where id = a.id and active= a.active and effectivetime = a.effectivetime and moduleid = a.moduleid and refsetid=a.refsetid and referencedcomponentid = a.referencedcomponentid
and descriptionformat = a.descriptionformat and descriptionlength = a.descriptionlength);
	commit;