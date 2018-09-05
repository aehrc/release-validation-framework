/********************************************************************************
	component-centric-snapshot-all-maps-referencedcomponentid-is-active-concept

	Assertion:
	All MAP REFSET SNAPSHOT files have valid, active SNOMED CT concepts in the ReferencedComponentId

********************************************************************************/
   insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('EXTENDED MAP REFSET SNAPSHOT: id=',a.id,' has inactive concept in ReferencedComponentId field = ',a.referencedcomponentid)
        from curr_extendedmaprefset_s a
        left join curr_concept_s b
        on  a.referencedcomponentid = b.id
        where b.id is not null and a.active = 1 and b.active = 0;
    commit;

    insert into qa_result (runid, assertionuuid, concept_id, details)
    select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('EXTENDED MAP REFSET SNAPSHOT: id=',a.id,' has invalid concept in ReferencedComponentId field = ',a.referencedcomponentid)
        from curr_extendedmaprefset_s a
        where a.active = 1 and a.referencedcomponentid not in (select id from curr_concept_s);
    commit;

    insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('COMPLEX MAP REFSET SNAPSHOT: id=',a.id,' has inactive concept in ReferencedComponentId field = ',a.referencedcomponentid)
        from curr_complexmaprefset_s a
        left join curr_concept_s b
        on  a.referencedcomponentid = b.id
        where b.id is not null and a.active = 1 and b.active = 0;
    commit;

    insert into qa_result (runid, assertionuuid, concept_id, details)
    select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('COMPLEX MAP REFSET SNAPSHOT: id=',a.id,' has invalid concept in ReferencedComponentId field = ',a.referencedcomponentid)
        from curr_complexmaprefset_s a
        where a.active = 1 and a.referencedcomponentid not in (select id from curr_concept_s);
    commit;

	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('SIMPLE MAP REFSET SNAPSHOT: id=',a.id,' has inactive concept in ReferencedComponentId field = ',a.referencedcomponentid)
        from curr_simplemaprefset_s a
        left join curr_concept_s b
        on  a.referencedcomponentid = b.id
        where b.id is not null and a.active = 1 and b.active = 0;
    commit;

    insert into qa_result (runid, assertionuuid, concept_id, details)
    select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('SIMPLE MAP REFSET SNAPSHOT: id=',a.id,' has invalid concept in ReferencedComponentId field = ',a.referencedcomponentid)
        from curr_simplemaprefset_s a
        where a.active = 1 and a.referencedcomponentid not in (select id from curr_concept_s);
    commit;