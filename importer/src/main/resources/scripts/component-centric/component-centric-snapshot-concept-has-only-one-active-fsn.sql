/********************************************************************************
	component-centric-snapshot-concept-has-only-one-active-fsn

	Assertion:
There must be exactly 1 active FSN per concept.

********************************************************************************/
insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.conceptid,
		concat('CONCEPT: id=',a.conceptid, ': There must be exactly 1 active FSN per concept, but this concept has ', count(a.id), ' active FSN')
from curr_description_s a
left join curr_concept_s b on b.id = a.conceptid
where a.active = 1
and b.active = 1
and a.typeid = 900000000000003001
group by a.conceptid
having count(a.id) != 1;