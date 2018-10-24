
/********************************************************************************
	component-centric-snapshot-rel-part_of-rel-group

	Assertion:
	All part-of relationships have relationship group of 0.

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.sourceid,
		concat('INFERRED RELATIONSHIP: id=',a.id, ': Inferred part-of relationship exists in a non-zero relationship group.')
	from curr_relationship_s a
	where a.typeid = '123005000 '
	and a.active = 1
	and a.relationshipgroup > 0;

	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.sourceid,
		concat('STATED RELATIONSHIP: id=',a.id, ': Stated part-of relationship exists in a non-zero relationship group.')
	from curr_stated_relationship_s a
	where a.typeid = '123005000 '
	and a.active = 1
	and a.relationshipgroup > 0;
