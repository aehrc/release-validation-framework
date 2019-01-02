/********************************************************************************
	file-centric-snapshot-simple-refset-contains-only-lateralizable

	Assertion:
	Verify that the simple refset files only contains Lateralizable refset (International Edition only)

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.moduleid,
		concat('Warning: Simple Refset id=',a.id,' has refsetId which is not Lateralizable refset (723264001) : ', a.refsetid)
	from curr_simplerefset_s a
	where a.active = 1
	and a.refsetid <> '723264001';
	commit;