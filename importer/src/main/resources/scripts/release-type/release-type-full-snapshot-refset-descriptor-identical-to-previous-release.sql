/********************************************************************************
	release-type-full-snapshot-refset-descriptor-identical-to-previous-release

	Assertion:
	- Verify that RefsetDescriptor Full and Snapshot files should be identical to last release

********************************************************************************/
insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		0,
		concat('Warning: RefsetDescriptor:',a.id,' is not found in last release RefsetDescriptor Snapshot file')
	from curr_refsetdescriptor_s a
	where not exists (select * from prev_refsetdescriptor_s where id = a.id and active = a.active and effectivetime = a.effectivetime and moduleid = a.moduleid and refsetid=a.refsetid and referencedcomponentid = a.referencedcomponentid
  and attributedescription = a.attributedescription and attributetype = a.attributetype and attributeorder = a.attributeorder);
	commit;

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		0,
		concat('Warning: RefsetDescriptor:',a.id,' is not found in last release RefsetDescriptor Full file')
	from curr_refsetdescriptor_f a
	where not exists (select * from prev_refsetdescriptor_f where id = a.id and active= a.active and effectivetime = a.effectivetime and moduleid = a.moduleid and refsetid=a.refsetid and referencedcomponentid = a.referencedcomponentid
  and attributedescription = a.attributedescription and attributetype = a.attributetype and attributeorder = a.attributeorder);
	commit;