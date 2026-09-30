
/******************************************************************************** 
	file-centric-snapshot-simple-refset-valid-key

	Assertion:
	There is a 1:1 relationship between the id and the key values in the SIMPLE REFSET snapshot.

	AU PATCH (assertions-au, ruling R7): only ACTIVE rows are grouped. A member
	retired under one UUID and re-added under another is published history, not
	a duplicate; two simultaneously active members for one key still fail.

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
		<RUNID>,
		'<ASSERTIONUUID>',
		a.referencedcomponentid,
		concat('Refset id:',a.refsetid, ' and referencedcomponent Id:', a.referencedcomponentid, ' are duplicated in the simple refset snapshot file.'),
		a.id,
		'curr_simplerefset_s'
	from curr_simplerefset_s a
	where a.active = '1'
	group by a.refsetid , a.referencedcomponentid
	having count(a.id) > 1;