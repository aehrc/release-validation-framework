
/********************************************************************************
	component-centric-snapshot-LOINC-concept-not-in-INT-release

	Assertion:
    Verify that LOINC concepts never make it into the International Edition. So we need to check for:
        a) ensure that no descriptions contain the words "LOINC Unique ID”, and
        b) ensure that there are no concepts in the LOINC module (715515008) with an FSN containing "(observable entity)".

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Concept Snapshot file with id = ',a.id,' contains description with the words LOINC Unique ID')
	from curr_concept_s a, curr_description_s b
	where a.id = b.conceptid
	and b.term like '%LOINC Unique ID%';
	commit;

	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Concept Snapshot file with id = ',a.id,' in the LOINC module contains an FSN with (observable entity)')
	from curr_concept_s a, curr_description_s b
	where a.id = b.conceptid
	and a.moduleid = 715515008
    and b.typeid = 900000000000003001
    and b.term like '%(observable entity)%';
	commit;
