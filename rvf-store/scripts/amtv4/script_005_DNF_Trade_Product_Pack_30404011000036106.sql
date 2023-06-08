/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some Trade Product Pack exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30404011000036106);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 Trade Product Pack and all of its descendants are 900000000000073002|Defined',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isKindOf_cr(id, 30404011000036106)           AND definitionstatusid != 900000000000073002                                         and id not in (1093401000168106,1093381000168106,33337011000036103,1165781000168101,1093371000168108,43828011000036102,1406701000168104,1406641000168107,1443351000168100,1443381000168107,1445561000168105,1445571000168104,1445591000168103,1445601000168105,1445631000168103,1445641000168107,1445661000168106,1445671000168100,1462191000168109,1462221000168103,1492621000168104,1492651000168107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All FSNs with semantic tag (trade product pack) are descendants of 30404011000036106|Trade Product Pack (or itself)',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId)           and typeId = 900000000000003001           AND term REGEXP BINARY ' \\(trade product pack\\)$'           and NOT isKindOf_cr(conceptId,30404011000036106 )           and conceptId != 30404011000036106;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 Trade Product Pack and all of its descendants have FSNs with a semantic tag (trade product pack) - except CTPP',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE isKindOf_cr(conceptid, 30404011000036106)              AND NOT isKindOf_cr(conceptid, 30537011000036101)           AND typeid = 900000000000003001           AND term NOT REGEXP BINARY ' \\(trade product pack\\)$';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 30404011000036106|TPP has FSN = trade product pack (trade product pack)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000003001           AND conceptId = 30404011000036106           AND term = BINARY 'trade product pack (trade product pack)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06a Concept 30404011000036106|TPP has SYN = trade product pack or TPP - trade product pack',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000013009           AND conceptId = 30404011000036106           AND term != BINARY 'trade product pack'           AND term != BINARY 'TPP - trade product pack';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b Concept 30404011000036106|TPP has SYN = trade product pack or TPP - trade product pack',
	null,
	null
select get_cr_ADRS_PT(30404011000036106) = BINARY 'trade product pack';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Trade Product Pack is a child of 30513011000036104|medicinal product pack',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceid = 30404011000036106           AND typeid = 116680003           AND destinationid = 30513011000036104;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 Trade Product Pack is only a child of 30513011000036104|medicinal product pack',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE     sourceid = 30404011000036106           AND typeid = 116680003           AND destinationid != 30513011000036104;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 [700000101000036108|has TP] relationships only have sourceIds that are descendants of of 30404011000036106|trade product pack (or itself)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000101000036108           and NOT isKindOf_cr(sourceId,30404011000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 [700000101000036108|has TP] relationships only have destinationIds that are children of 30560011000036108|trade product (or itself)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE     typeId = 700000101000036108           AND NOT isChildOf_cr(destinationid, 30560011000036108)           AND destinationid != 30560011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 Trade Product Pack and its descendants have exactly one [700000101000036108|has TP] relationship',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 700000101000036108           AND isKindOf_cr(sourceId, 30404011000036106)           GROUP BY sourceid           HAVING count(*) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 [700000101000036108|has TP] relationship is never grouped',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 700000101000036108           AND isKindOf_cr(sourceId, 30404011000036106)           AND relationshipgroup != 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 [30409011000036107|has TPUU] relationships only have sourceIds that are descendants of 30404011000036106|trade product pack (or itself)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30409011000036107           and NOT isKindOf_cr(sourceId,30404011000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 [30409011000036107|has TPUU] relationships only have destinationIds that are children of 30425011000036101|trade product unit of use (or itself)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 30409011000036107           AND NOT isKindOf_cr(destinationid, 30425011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 Every Trade Product Pack  has at least one [30409011000036107|has TPUU] relationship',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isKindOf_cr(id, 30404011000036106)           AND id NOT IN (SELECT sourceid               FROM <PROSPECTIVE>.relationship_active               WHERE typeId = 30409011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 Every [30409011000036107|has TPUU] relationship IS referenced in the Unit of use quantity reference set(700000131000036101) - except 30404011000036106|TPP and 30537011000036101|CTPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30409011000036107           and sourceId not in (30404011000036106,30537011000036101)           and id not in (select referencedComponentId from <PROSPECTIVE>.ccsrefset_<SNAPSHOT> where active = 1 and refsetId = 700000131000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 Every [30409011000036107|has TPUU] relationship is always grouped',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 30409011000036107           AND isKindOf_cr(sourceId, 30404011000036106)           AND relationshipgroup = 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 [30454011000036104|has subpack] relationships for TPPs only have destinationIds that are subtypes of TPP',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 30454011000036104                                         and isKindOf_cr(sourceId, 30425011000036101)            AND NOT isKindOf_cr(destinationid, 30425011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 Every [30454011000036104|has subpack] relationship IS referenced in the Subpack quantity reference set(700000121000036103)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           and id not in (select referencedComponentId from <PROSPECTIVE>.ccirefset_<SNAPSHOT> where active = 1 and refsetId = 700000121000036103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 [30454011000036104|has subpack] relationship is always grouped',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 30454011000036104           AND isKindOf_cr(sourceId, 30404011000036106)           AND relationshipgroup = 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 [700000061000036106|has componentPack] relationships for TPPs only have destinationIds that are subtypes of TPP',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 700000061000036106                                         and isKindOf_cr(sourceId, 30425011000036101)            AND NOT isKindOf_cr(destinationid, 30425011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 Every Concept with [700000061000036106|has componentPack] relationship has at least 2 of them',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where typeId = 700000061000036106                                         group by sourceId                                         having count(*) < 2;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 [700000061000036106|has componentPack] relationship is never grouped',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 700000061000036106           AND isKindOf_cr(sourceId, 30404011000036106)           AND relationshipgroup != 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 Trade Product Pack and its children only have [116680003|Is a] OR [30409011000036107|has TPUU] OR [700000101000036108|has TP] relationships OR 30454011000036104 [has subpack] OR 700000061000036106 [has component pack]',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceId, 30404011000036106)           AND NOT isKindOf_cr(sourceId, 30537011000036101)           AND typeId NOT IN (116680003, 30409011000036107, 700000101000036108,30454011000036104,700000061000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'20 All Trade Product Pack have at least one 30513011000036104|medicinal product pack parent - except 30537011000036101|CTPP',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isKindOf_cr(Id, 30404011000036106) and NOT isKindOf_cr(Id, 30537011000036101)           AND id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 116680003               and isKindOf_cr(destinationId, 30513011000036104) and NOT isKindOf_cr(Id, 30404011000036106)                 );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'21 DNF 30404011000036106|TPPs with 700000061000036106|hasComponentPack relationships, have at least 2',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000061000036106           and isDescendentOf_cr(sourceId,30404011000036106) and not isDescendentOf_cr(sourceId,30537011000036101)           group by sourceId           having count(id) < 2;

