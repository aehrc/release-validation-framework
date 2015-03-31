
/******************************************************************************** 
	file-centric-snapshot-definition-unique-id

	Assertion:
	ID is unique in the DEFINITION snapshot.

********************************************************************************/
	
/* 	view of current snapshot made by finding duplicate ids */
	drop table if exists v_curr_snapshot;
	create table if not exists  v_curr_snapshot as
	select a.id
	from curr_textdefinition_s a	
	group by a.id
	having  count(a.id) > 1;
	

	
/* 	inserting exceptions in the result table */
	insert into qa_result (runid, assertionuuid, assertiontext, details)
	select 
		<RUNID>,
		'<ASSERTIONUUID>',
		'<ASSERTIONTEXT>',
		concat('TextDefinition: id=',a.id, ' is not unique in the TextDefinition Snapshot file.') 	
	from v_curr_snapshot a;


	drop table if exists v_curr_snapshot;
