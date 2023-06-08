insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 Multipack TPPs dont subsume non-multipack TPPs',
	null,
	null
from (select destinationId,sourceId from <PROSPECTIVE>.relationship_active                                                 where typeId = 116680003                                                 and isActiveMemberOf_cr_refset(sourceId, 929360041000036105) and isActiveMemberOf_cr_refset(destinationId, 929360041000036105)                                                 ) AS TPP_TPP                                         JOIN (select sourceId,count(*) MPUUs from <PROSPECTIVE>.relationship_active where typeId = 30409011000036107 group by sourceId) AS TPP1_QTY                                         on TPP_TPP.sourceId = TPP1_QTY.sourceId                                         JOIN (select sourceId,count(*) MPUUs from <PROSPECTIVE>.relationship_active where typeId = 30409011000036107 group by sourceId) AS TPP2_QTY                                         on TPP_TPP.destinationId = TPP2_QTY.sourceId                                         where TPP1_QTY.MPUUs < TPP2_QTY.MPUUs;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 Multipack MPPs dont subsume non-multipack MPPs',
	null,
	null
from (select destinationId,sourceId from <PROSPECTIVE>.relationship_active                                                 where typeId = 116680003                                                 and isActiveMemberOf_cr_refset(sourceId, 929360081000036101) and isActiveMemberOf_cr_refset(destinationId, 929360081000036101)                                                 ) AS MPP_MPP                                         JOIN (select sourceId,count(*) MPUUs from <PROSPECTIVE>.relationship_active where typeId = 30348011000036104 group by sourceId) AS MPP1_QTY                                         on MPP_MPP.sourceId = MPP1_QTY.sourceId                                         JOIN (select sourceId,count(*) MPUUs from <PROSPECTIVE>.relationship_active where typeId = 30348011000036104 group by sourceId) AS MPP2_QTY                                         on MPP_MPP.destinationId = MPP2_QTY.sourceId                                         where MPP1_QTY.MPUUs < MPP2_QTY.MPUUs;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 TPPs have the same number of hasUU as their corresponding MPUU',
	null,
	null
from (select destinationId,sourceId from <PROSPECTIVE>.relationship_active                                         where typeId = 116680003                                         and isActiveMemberOf_cr_refset(sourceId, 929360041000036105) and isActiveMemberOf_cr_refset(destinationId, 929360081000036101)                                                             ) AS MPP_TPP                                         JOIN (select sourceId,count(*) TPUUs from <PROSPECTIVE>.relationship_active where typeId = 30409011000036107 group by sourceId) AS TPUU_QTY                                         on MPP_TPP.sourceId = TPUU_QTY.sourceId                                         JOIN (select sourceId,count(*) MPUUs from <PROSPECTIVE>.relationship_active where typeId = 30348011000036104 group by sourceId) AS MPUU_QTY                                         on MPP_TPP.destinationId = MPUU_QTY.sourceId                                         where MPUUs != TPUUs;

