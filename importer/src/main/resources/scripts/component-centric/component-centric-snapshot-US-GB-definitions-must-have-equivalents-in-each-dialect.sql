
/********************************************************************************
	component-centric-snapshot-US-GB-definitions-must-have-equivalents-in-each-dialect.sql

	Assertion:
	US and GB text definitions should have equivalents in each dialect

********************************************************************************/
insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		c.id,
		concat('CONCEPT: id=', c.id, ': Text Definition ID=', a.referencedcomponentid, ' does not have equivalent number of US and GB dialect')
from
curr_langrefset_s a
left join curr_textdefinition_s b on a.referencedcomponentid = b.id
left join curr_concept_s c on b.conceptid = c.id
where
a.active = 1
and b.active = 1
and c.active = 1
and (select count(refsetid) from curr_langrefset_s
where refsetid = '900000000000509007'
and acceptabilityid = '900000000000548007'
and referencedcomponentid = a.referencedcomponentid)
 <>
(select count(refsetid) from curr_langrefset_s
where refsetid = '900000000000508004'
and acceptabilityid = '900000000000548007'
and referencedcomponentid = a.referencedcomponentid)
group by c.id, a.referencedcomponentid;
