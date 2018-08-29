/********************************************************************************
	file-centric-snapshot-refset-descriptor-referencedcomponentid-attributeorder-unique.sql

	Assertion:
	referencedComponentId + attributeOrder pair is unique in REFSET DESCRIPTOR SNAPSHOT

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset: id=',a.id,' with ReferencedComponentId = ',a.referencedcomponentid,' + AttributeOrder = ',a.attributeorder,'  is not unique pair in REFSET DESCRIPTOR SNAPSHOT')
	from curr_refsetdescriptor_s a
    where (a.referencedcomponentid,a.attributeorder,a.active) in (
													select b.referencedcomponentid,b.attributeorder,b.active
													from curr_refsetdescriptor_s b
													group by b.referencedcomponentid,b.attributeorder,b.active
													having count(*) > 1
													);
	commit;