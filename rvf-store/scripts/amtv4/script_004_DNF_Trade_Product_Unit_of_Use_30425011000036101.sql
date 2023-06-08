/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some Trade Product Unit of Use exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30425011000036101);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All concepts with semantic tag (trade product unit of use) are Types of 30425011000036101|trade product unit of use only',
	null,
	null
from <PROSPECTIVE>.description_active            where isActiveConcept_cr(conceptId)           and typeId = 900000000000003001           AND term REGEXP BINARY ' \\(trade product unit of use\\)$'            and NOT (isKindOf_cr(conceptId, 30425011000036101) AND NOT isKindOf_cr(conceptId, 30537011000036101));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 Trade Product Unit of Use and all of its descendants have FSNs with a semantic tag (trade product unit of use)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE (isKindOf_cr(conceptid, 30425011000036101)           OR conceptid = 30425011000036101)           AND typeid = 900000000000003001           AND term NOT REGEXP BINARY ' \\(trade product unit of use\\)$';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 30425011000036101|TPUU has FSN = trade product unit of use (trade product unit of use)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE typeId = 900000000000003001           AND conceptId = 30425011000036101           AND term != BINARY 'trade product unit of use (trade product unit of use)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06a Concept 30425011000036101|TPUU has SYN = trade product unit of use or TPUU - trade product unit of use',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE typeId = 900000000000013009           AND conceptId = 30425011000036101           AND term != BINARY 'trade product unit of use'           AND term != BINARY 'TPUU - trade product unit of use';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b Concept 30425011000036101|TPUU has SYN = trade product unit of use or TPUU - trade product unit of use',
	null,
	null
select get_cr_ADRS_PT(30425011000036101) = BINARY 'trade product unit of use';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Trade Product Unit of Use is a child of 30560011000036108|trade product',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceid = 30425011000036101           AND typeid = 116680003           AND destinationid = 30560011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 Trade Product Unit of Use is a child of 30450011000036109|medicinal product unit of use',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceid = 30425011000036101           AND typeid = 116680003           AND destinationid = 30450011000036109;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 Trade Product Unit of Use is only a child of 30450011000036109|medicinal product unit of use OR 30560011000036108|trade product',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceid = 30425011000036101           AND typeid = 116680003           AND NOT destinationid in (30450011000036109,30560011000036108);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 All destinationids of 30523011000036108|has manufactured dose form relationships of Trade Product Unit of Use concepts are types of the form stated with the associated MPUU',
	null,
	null
FROM <PROSPECTIVE>.relationship_active r1           JOIN <PROSPECTIVE>.relationship_active r2           JOIN <PROSPECTIVE>.relationship_active r3           ON     r1.sourceid = r2.sourceid           AND r1.typeid = 30523011000036108           AND r2.typeid = 116680003           AND r3.sourceid = r2.destinationid           AND r3.typeid = 30523011000036108           WHERE     isKindOf_cr(r1.sourceid, 30425011000036101)           AND isKindOf_cr(r2.destinationid, 30450011000036109)           AND NOT isKindOf_cr(r1.destinationid, r3.destinationid);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 All children of Trade Product Unit of Use are the destination of at least one [30409011000036107|has TPUU] relationship',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isChildOf_cr(Id, 30425011000036101)           AND id NOT IN (SELECT destinationid               FROM <PROSPECTIVE>.relationship_active               WHERE typeId = 30409011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'21 All Trade Product Unit of Use are the child of at least one 30450011000036109 [medicinal product unit of use]',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isKindOf_cr(Id, 30425011000036101)           AND id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 116680003               and isKindOf_cr(destinationId, 30450011000036109) and NOT isKindOf_cr(destinationId, 30425011000036101)                 );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 [116680003|Is a] relationships of children of Trade Product Unit of Use concepts only have destinationIds that are children of 30560011000036108|trade product or 30450011000036109|medicinal product unit of use Concepts',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isDescendentOf_cr(sourceId, 30425011000036101)           and typeId = 116680003           and destinationid != 30425011000036101           and NOT isChildOf_cr(destinationid, 30560011000036108)           and not (             isDescendentOf_cr(destinationid, 30450011000036109)             AND NOT isKindOf_cr(destinationid, 30425011000036101)             );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 Every TPUU has at least 1 has intended active ingredient(700000081000036101) relationship',
	null,
	null
from <PROSPECTIVE>.concept_active AS C           where isKindOf_cr(id,30425011000036101)           and id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 700000081000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 Every TPUU has at least 1 has manufactured dose form(30523011000036108) relationship',
	null,
	null
from <PROSPECTIVE>.concept_active AS C           where id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 30523011000036108)           and isKindOf_cr(id,30425011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 Every TPUU has Exactly 1 has manufactured dose form(30523011000036108) relationship',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30523011000036108           and isKindOf_cr(sourceId,30425011000036101)           group by sourceId           having count(typeId) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 Every Kind of TPUU has at least 1 has unit of use(30548011000036101) relationship',
	null,
	null
from <PROSPECTIVE>.concept_active AS C           where isKindOf_cr(id,30425011000036101)           and id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 30548011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'18 Every TPUU has Exactly 1 has unit of use(30548011000036101) relationship',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30548011000036101           and isKindOf_cr(sourceId,30425011000036101)           group by sourceId           having count(typeId) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'19 ALL TPUU only have [700000081000036101|has intended active ingredient]                   OR [116680003|Is a]                    OR [30364011000036101|has Australian BoSS]                    OR [30523011000036108|has manufactured dose form]                    OR [30548011000036101|has unit of use] relationships',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId,30425011000036101)           and typeId not in (700000081000036101,116680003,30364011000036101,30523011000036108,30548011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'20 DNF All TPUUs are disjoint (TPUUs arent subtypes of other TPUUs)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 116680003           AND isDescendentof_cr(sourceId,30425011000036101)           AND isDescendentof_cr(destinationId,30425011000036101);

