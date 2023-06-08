/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01b DNF Some Units of Measure exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30428011000036107 or sourceid = 30428011000036107);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All Unit of Measure concepts are 900000000000074008|primitive (Except Composite Unit of Measure (700000041000036105))',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE isKindOf_cr(id, 30428011000036107)           AND definitionstatusid != 900000000000074008           AND NOT isKindOf_cr(id, 700000041000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All Unit of Measure FSNs have a semantic tag (AU qualifier)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE isActiveConcept_cr(conceptId) AND isKindOf_cr(conceptid, 30428011000036107)           AND typeid = 900000000000003001           AND term NOT REGEXP BINARY ' \\(AU qualifier\\)$';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 Concept 30428011000036107|unit of measure has FSN = unit of measure (AU qualifier)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000003001           AND conceptId = 30428011000036107           AND term != BINARY 'unit of measure (AU qualifier)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 30428011000036107|unit of measure has SYN = unit of measure',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000013009           AND conceptId = 30428011000036107           AND term != BINARY 'unit of measure';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b DNF unit of measure(30428011000036107) is a child of 30515011000036103|Australian qualifier',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceId = 30428011000036107           AND typeId = 116680003           AND destinationId = 30515011000036103;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07b DNF unit of measure(30428011000036107) is only a child of 30515011000036103|Australian qualifier',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceId = 30428011000036107           AND typeId = 116680003           AND destinationId != 30515011000036103;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08b DNF Unit of Measure concepts (excluding composite units of measure) only have [116680003|Is a] relationships',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceid, 30428011000036107)           AND NOT isKindOf_cr(sourceid, 700000041000036105)           AND typeid != 116680003;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09b DNF Unit of Measure descendents only have destinationIds that are types of 30428011000036107|Unit of Measure',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isDescendentOf_cr(sourceid, 30428011000036107)           AND NOT isKindOf_cr(destinationid, 30428011000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10b DNF All Unit of Measure relationships are ungrouped (group = 0)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceid, 30428011000036107)           AND relationshipgroup != 0;

