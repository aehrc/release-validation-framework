/********************************************************************************
	component-centric-snapshot-extended-maps-referencedcomponentid-is-active-concept

	Assertion:
	Extended Map Refset Snapshot files has existing SNOMED CT concepts in the ReferencedComponentId

********************************************************************************/

    insert into qa_result (runid, assertionuuid, concept_id, details)
    select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('EXTENDED MAP REFSET SNAPSHOT: id=',a.id,' has invalid concept in ReferencedComponentId = ', a.referencedcomponentid)
        from curr_extendedmaprefset_s a
        where a.active = 1 and a.referencedcomponentid not in (select id from curr_concept_s);
    commit;
