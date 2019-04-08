
/******************************************************************************** 
	file-centric-snapshot-concept-right-module.sql
	Assertion:
	Snapshot concepts assigned to the right module.

********************************************************************************/
	insert into qa_result (runid, assertionuuid, concept_id, details)
	select 
		<RUNID>,
		'<ASSERTIONUUID>',
		cc.id,
		concat('Child concept id= ', cc.id, ' of core module has parent concept id= ', pc.id, ' of model module.')
	from concept_s cc, concept_s pc, relationship_s r
    where cc.active = 1
    and pc.active = 1
    and r.active = 1
    and r.sourceid = cc.id
    and r.destinationid = pc.id
    and r.typeid = '116680003' /* IS A */
    and pc.moduleid = '900000000000012004' /* Model Module */
    and cc.moduleid = '900000000000207008'; /* Core Module */
	