/********************************************************************************
	component-centric-snapshot-extendedmap-contains-only-ICD10

	Assertion:
	Extended Map Snapshot file contains nothing except ICD-10 maps (International Edition only)

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset with id = ',a.id,' and refsetId = ',a.refsetid,' in Extended Map Snapshot file is not an ICD-10 map')
	from curr_extendedmaprefset_s a
	where a.refsetid not in (447562003);
	commit;