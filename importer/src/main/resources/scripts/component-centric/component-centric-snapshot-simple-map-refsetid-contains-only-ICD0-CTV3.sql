/********************************************************************************
	component-centric-snapshot-simple-map-refsetid-contains-only-ICD0-CTV3

	Assertion:
	Simple Map Snapshot file contains both ICD-0 + CTV3 maps, but nothing else (International Edition only)

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset with id = ',a.id,' and refsetId = ',a.refsetid,' in Simple Map Snapshot file is not ICD-0 or CTV3 map')
	from curr_simplemaprefset_s a
	where a.refsetid not in (900000000000497000,446608001);
	commit;