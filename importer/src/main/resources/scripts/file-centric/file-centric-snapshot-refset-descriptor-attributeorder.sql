/********************************************************************************
	file-centric-snapshot-refset-descriptor-attributeorder

	Assertion:
	- AttributeOrder in REFSET DESCRIPTOR SNAPSHOT is always set to value > = 0
	- For each referencedComponentId there is one record where attributeOrder = 0 in REFSET DESCRIPTOR SNAPSHOT

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('AttributeOrder of: id=',a.id,' in REFSET DESCRIPTOR SNAPSHOT is not set to value >= 0')
	from curr_refsetDescriptor_s a
	where a.attributeorder < 0;
	commit;

insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset: id=',a.id,' ReferencedComponentId = ',a.referencedcomponentid,' has more than one record where attributeOrder = 0 in REFSET DESCRIPTOR SNAPSHOT')
	from curr_refsetDescriptor_s a, (
                                    select  b.referencedcomponentid ,count(*) total
                                    from curr_refsetDescriptor_s b
                                    where b.attributeorder=0
                                    group by b.referencedcomponentid
                                    having count(*) > 1
                                    ) c
    where a.referencedcomponentid=c.referencedcomponentid
    and  a.attributeorder=0;
	commit;


	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Refset: id=',a.id,' ReferencedComponentId = ',a.referencedcomponentid,' has no record where attributeOrder = 0 in REFSET DESCRIPTOR SNAPSHOT')
	from
	    curr_refsetDescriptor_s a, (
            select  referencedcomponentid ,attributeorder,count(*) total
            from curr_refsetDescriptor_s
            group by referencedcomponentid  ,attributeorder
        ) b
        where a.referencedcomponentid=b.referencedcomponentid
        and a.attributeorder=b.attributeorder
        and not exists (
            select 1
            from curr_refsetDescriptor_s c
            where c.referencedcomponentid=a.referencedcomponentid
            and c.attributeorder =  0 );
    commit;