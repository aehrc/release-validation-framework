/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01b DNF Some Trade Products exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30560011000036108 or sourceid = 30560011000036108);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All children of 30560011000036108|trade product (and itself) are 900000000000074008|primitive (Except 30425011000036101|TPUU)',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE     (isChildOf_cr(id, 30560011000036108)           OR id = 30560011000036108)           AND id != 30425011000036101           AND definitionstatusid != 900000000000074008;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All FSNs with semantic tag (trade product) are children of 30560011000036108|trade product (or itself)',
	null,
	null
from <PROSPECTIVE>.description_active            where isActiveConcept_cr(conceptId) AND typeId = 900000000000003001           AND term REGEXP BINARY ' \\(trade product\\)$'            and NOT isChildOf_cr(conceptId,30560011000036108) and conceptId != 30560011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All children of 30560011000036108|trade product (and itself but not 30425011000036101|TPUU) FSNs have a semantic tag (trade product)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE  (isChildOf_cr(conceptid, 30560011000036108)           OR conceptid = 30560011000036108)           AND conceptid != 30425011000036101           AND typeid = 900000000000003001           AND term NOT REGEXP BINARY ' \\(trade product\\)$';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 30560011000036108|trade product has FSN = trade product (trade product)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000003001           AND conceptId = 30560011000036108           AND term != BINARY 'trade product (trade product)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 Concept 30560011000036108|trade product has SYN = trade product',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000013009           AND conceptId = 30560011000036108           AND term != BINARY 'trade product'           AND term != BINARY 'TP - trade product';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07a Stated All Trade products are descendents of 30506011000036107|Australian product',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isKindOf_cr(id, 30560011000036108)           AND NOT isDescendentOf_cr(id, 30506011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07b DNF All Trade products are descendents of 30506011000036107|Australian product',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isKindOf_cr(id, 30560011000036108)           AND NOT isDescendentOf_cr(id, 30506011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08b DNF trade product(30560011000036108) is a child of 30506011000036107|Australian product',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceId = 30560011000036108           AND typeId = 116680003           AND destinationId = 30506011000036107;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09b DNF Trade Product(30560011000036108) is only a child of 30506011000036107|Australian product',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceId = 30560011000036108           AND typeId = 116680003           AND destinationId != 30506011000036107;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10b DNF Children of 30560011000036108|trade product (and itself) only have 116680003|Is a relationships',
	null,
	null
FROM <PROSPECTIVE>.relationship_active            WHERE isKindOf_cr(sourceid, 30560011000036108) and NOT isKindOf_cr(sourceid, 30425011000036101)           AND typeid != 116680003;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11b DNF Children of 30560011000036108|trade product (except 30425011000036101|TPUU) only have a destinationId of 30560011000036108|Trade product',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isChildOf_cr(sourceid, 30560011000036108)           AND sourceid != 30425011000036101           AND destinationid != 30560011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12b DNF Children of 30560011000036108|trade product relationships are ungrouped (group = 0) (ex. TPUU)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isChildOf_cr(sourceid, 30560011000036108)           AND relationshipgroup != 0           and sourceId != 30425011000036101;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13b DNF All children of Trade Product are the destination of at least one [700000101000036108|has TP] OR 116680003 [Is a] relationship',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isChildOf_cr(Id, 30560011000036108)           AND id NOT IN (SELECT destinationid               FROM <PROSPECTIVE>.relationship_active               WHERE typeId in (700000101000036108,116680003));

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14b DNF All TPs are disjoint (TPs arent subtypes of other TPs)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where (isDescendentOf_cr(sourceId, 30560011000036108) and not isKindOf_cr(sourceId, 30425011000036101))           and (isDescendentOf_cr(destinationid, 30560011000036108) and not isKindOf_cr(destinationid, 30425011000036101));

