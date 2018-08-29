/********************************************************************************
	file-centric-snapshot-refset-descriptor-set-of-attributeorder.sql

	Assertion:
	For each referencedComponentId group by referencedComponentId in REFSET DESCRIPTOR SNAPSHOT,
	the set of attributeOrder values is a consecutive sequence starting at 0

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('ReferencedComponentId=',a.referencedcomponentid,' in REFSET DESCRIPTOR SNAPSHOT is not a consecutive sequence starting at 0')
	from
    (
        select *,
            (select MIN(t3.attributeorder) -1 from curr_refsetDescriptor_s t3 where t3.referencedcomponentid=t1.referencedcomponentid and t3.attributeorder > t1.attributeorder) as gap_ends_at
            from curr_refsetDescriptor_s t1
            where not exists  (select id from curr_refsetDescriptor_s t2 where  t2.referencedcomponentid=t1.referencedcomponentid and t2.attributeorder = t1.attributeorder + 1)
            group by referencedcomponentid
            having gap_ends_at is not null
        union
            select *,
            (select MIN(t3.attributeorder) -1 from curr_refsetDescriptor_s t3 where t3.referencedcomponentid=t1.referencedcomponentid and t3.attributeorder > t1.attributeorder) as gap_ends_at
            from curr_refsetDescriptor_s t1
            where attributeorder < 0
            group by referencedcomponentid
    ) a;
	commit;