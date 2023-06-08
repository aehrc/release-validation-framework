/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some Containered Trade Product Pack exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30537011000036101);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 Containered Trade Product Pack and all of its descendants are 900000000000073002|Defined',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isKindOf_cr(id, 30537011000036101)           AND definitionstatusid != 900000000000073002                                         and id not in (1093401000168106,1093381000168106,1165781000168101,43828011000036102,1406701000168104,1406641000168107,1443351000168100,1443381000168107,1445571000168104,1445601000168105,1445641000168107,1445671000168100,1462191000168109,1462221000168103,1492621000168104,1492651000168107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All concepts with semantic tag (containered trade product pack) are descendants of 30537011000036101|Containered Trade Product Pack (or itself)',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId)           and typeId = 900000000000003001           AND term REGEXP BINARY ' \\(containered trade product pack\\)$'           and NOT isKindOf_cr(conceptId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 Containered Trade Product Pack and all of its children have FSNs with a semantic tag (containered trade product pack)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE  isKindOf_cr(conceptid, 30537011000036101)           AND typeid = 900000000000003001           AND term NOT REGEXP BINARY ' \\(containered trade product pack\\)$';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 30537011000036101|CTPP has FSN = containered trade product pack (containered trade product pack)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000003001           AND conceptId = 30537011000036101           AND term = BINARY 'containered trade product pack (containered trade product pack)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06a Concept 30537011000036101|CTPP has SYN = containered trade product pack or CTPP - containered trade product pack',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000013009           AND conceptId = 30537011000036101           AND term != BINARY 'containered trade product pack'           AND term != BINARY 'CTPP - containered trade product pack';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b Concept 30537011000036101|CTPP has SYN = containered trade product pack or CTPP - containered trade product pack',
	null,
	null
select get_cr_ADRS_PT(30537011000036101) = BINARY 'containered trade product pack';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Containered Trade Product Pack is a child of 30404011000036106|trade product pack',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE     sourceid = 30537011000036101           AND typeid = 116680003           AND destinationid = 30404011000036106;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 Containered Trade Product Pack is only a child of 30404011000036106|trade product pack OR 30506011000036107|Australian product',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceid = 30537011000036101           AND typeid = 116680003           AND NOT destinationid in (30404011000036106,30506011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 [30465011000036106|has container type] relationships only have sourceIds that are types of 30537011000036101|containered trade product pack (or itself)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30465011000036106           and NOT isKindOf_cr(sourceId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 [30465011000036106|has container type] relationships only have destinationIds that are descendents of 30446011000036105|container type (or itself)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 30465011000036106           AND NOT isKindOf_cr(destinationid, 30446011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12a All children of Containered Trade Product Pack no more than one [30465011000036106|has container type] relationship',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 30465011000036106           AND isChildOf_cr(sourceId, 30537011000036101)           GROUP BY sourceid           HAVING count(*) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12b All children of Containered Trade Product Pack at least one [30465011000036106|has container type] relationship',
	null,
	null
from <PROSPECTIVE>.concept_active           where isKindOf_cr(id, 30537011000036101)           and id not in (SELECT distinct sourceid FROM <PROSPECTIVE>.relationship_active                WHERE typeId = 30465011000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 All destinationids of [30454011000036104|has subpack] relationships of CTPP concepts are types of the destinationids for the related MPP has subpack relationship',
	null,
	null
FROM <PROSPECTIVE>.relationship_active r1           JOIN <PROSPECTIVE>.relationship_active r2           JOIN <PROSPECTIVE>.relationship_active r3           JOIN <PROSPECTIVE>.relationship_active r4           ON     r1.sourceid = r2.sourceid           AND r2.destinationid = r3.sourceid           AND r3.destinationid = r4.sourceid           AND r1.typeid = 30454011000036104           AND r2.typeid = 116680003           AND r3.typeid = 116680003           AND r4.typeid = 30454011000036104           WHERE isKindOf_cr(r1.sourceid, 30537011000036101)           AND (isChildOf_cr(r3.destinationid, 30513011000036104)           OR r3.destinationid = 30513011000036104)           AND NOT isKindOf_cr(r1.destinationid, r4.destinationid);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 [30454011000036104|has subpack] relationships of Containered Trade Product Pack concepts only have destinationIds that are types of 30537011000036101|containered trade product pack',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId, 30537011000036101)           AND typeId = 30454011000036104           and NOT isKindOf_cr(destinationId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 [30454011000036104|has subpack] relationships with destination CTPP concepts only have sourceIDs that are 30537011000036101|CTPPs',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(destinationId, 30537011000036101)           AND typeId = 30454011000036104           and NOT isKindOf_cr(sourceId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 Maximum of 1 has subpack (30454011000036104) for all kinds of 30537011000036101|CTPP',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 30454011000036104           AND isKindOf_cr(sourceid, 30537011000036101)           GROUP BY sourceId           HAVING count(*) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'18 All destinationids of [700000061000036106|has component pack] relationships of CTPP concepts are types of the destinationids for the related MPP has component pack relationship',
	null,
	null
from <PROSPECTIVE>.relationship_active AS MPPs           join <PROSPECTIVE>.relationship_active AS CTPPs on MPPs.active = CTPPs.active           where IsKindOf_cr(MPPs.sourceId,30513011000036104)           and MPPs.typeId = 700000061000036106           and CTPPs.typeId = 700000061000036106           and isAncestorOf_cr(MPPs.sourceId,CTPPs.sourceId)           and NOT isAncestorOf_cr(MPPs.destinationId,CTPPs.destinationId)            and MPPs.destinationId not in (select destinationId from <PROSPECTIVE>.relationship_active As nested_MPP                   where nested_MPP.sourceId = MPPs.sourceId                   and nested_MPP.typeId = 700000061000036106                   );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'19 [700000061000036106|has component pack] relationships of Containered Trade Product Pack concepts only have destinationIds that are kinds of 30537011000036101|containered trade product pack',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId, 30537011000036101)           AND typeId = 700000061000036106           and NOT isKindOf_cr(destinationId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'21 If destinationId is kind of 30537011000036101|CTPP, all has component pack (700000061000036106) sourceId are also kinds of 30537011000036101|CTPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000061000036106           and isKindOf_cr(destinationId,30537011000036101)           and NOT isKindOf_cr(sourceId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'22 All Containered Trade Product Pack only have [116680003|Is a] OR [700000061000036106|has component pack] OR [30454011000036104|has subpack] OR [30465011000036106|has container type] 700000101000036108 [has TP] OR 30348011000036104 [has MPUU] OR 30409011000036107 [has TPUU] relationships',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE    isKindOf_cr(sourceId, 30537011000036101)           AND typeId NOT IN (116680003, 700000061000036106, 30454011000036104, 30465011000036106,30348011000036104,700000101000036108,30409011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'23 Containered Trade Product Pack concepts only have [116680003|Is a] relationships to 30404011000036106|trade product pack concepts or 30537011000036101|CTPP',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isDescendentOf_cr(sourceId, 30537011000036101)           AND typeId = 116680003           AND destinationID not in (                           select id from <PROSPECTIVE>.concept_active                 where isKindOf_cr(id, 30404011000036106)                 and NOT isDescendentOf_cr(id, 30537011000036101)                 );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'24 All Containered Trade Product Pack relationships except HasTP, IS A, hasComponentPack, ContainerType are grouped',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceid, 30537011000036101)           and typeId not in (116680003,30465011000036106,700000061000036106,700000101000036108)           AND relationshipgroup = 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'24 Containered Trade Product Pack relationships - HasTP, IS A, hasComponentPack, ContainerType are never grouped',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceid, 30537011000036101)           and typeId in (116680003,30465011000036106,700000061000036106,700000101000036108)           AND relationshipgroup != 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'25 DNF All CTPPs are disjoint (CTPPs arent subtypes of other CTPPs)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 116680003           AND isDescendentof_cr(sourceId,30537011000036101)           AND isDescendentof_cr(destinationId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'26 DNF 30537011000036101|CTPPs with 700000061000036106|hasComponentPack relationships, have at least 2',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000061000036106           and isDescendentOf_cr(sourceId,30537011000036101)           group by sourceId           having count(id) < 2;

