/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01b DNF Some Composite Unit of Measure concepts exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 700000041000036105 or sourceid = 700000041000036105);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All Composite Unit of Measure concepts are 900000000000073002|Defined',
	null,
	null
FROM <PROSPECTIVE>.concept_active           WHERE     isKindOf_cr(id, 700000041000036105)           AND definitionstatusid != 900000000000073002;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All Composite Unit of Measure FSNs have a semantic tag (AU qualifier)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE isActiveConcept_cr(conceptId) AND isKindOf_cr(conceptid, 700000041000036105)           AND typeid = 900000000000003001           AND term NOT REGEXP BINARY ' \\(AU qualifier\\)$';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 Concept 700000041000036105|composite unit of measure has FSN = composite unit of measure (AU qualifier)',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000003001           AND conceptId = 700000041000036105           AND term != BINARY 'composite unit of measure (AU qualifier)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 700000041000036105|composite unit of measure has SYN = composite unit of measure',
	null,
	null
FROM <PROSPECTIVE>.description_active           WHERE     typeId = 900000000000013009           AND conceptId = 700000041000036105           AND term != BINARY 'composite unit of measure';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b DNF composite unit of measure(700000041000036105) is a child of 30428011000036107|unit of measure',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceId = 700000041000036105           AND typeId = 116680003           AND destinationId = 30428011000036107;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07b DNF composite unit of measure(700000041000036105) is only a child of 30428011000036107|unit of measure',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE sourceId = 700000041000036105           AND typeId = 116680003           AND destinationId != 30428011000036107;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08b DNF[16680003|Is a relationships] of Composite Unit of Measure descendents only have destinationIds that are types of 700000041000036105|composite unit of measure',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isDescendentOf_cr(sourceid, 700000041000036105)           AND typeid = 116680003           AND NOT isKindOf_cr(destinationid, 700000041000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09b DNF [700000071000036103|has denominator units] relationships only have destinationIds that are types of 30428011000036107|unit of measure (excluding types of Composite Unit of Measure)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeid = 700000071000036103           AND NOT isKindOf_cr(destinationid, 30428011000036107)           AND NOT isKindOf_cr(destinationid, 700000041000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10b DNF [700000071000036103|has denominator units] relationships only have sourceIds that are types of 700000041000036105|composite unit of measure',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeid = 700000071000036103           AND NOT isKindOf_cr(sourceid, 700000041000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11b DNF All Composite Unit of Measure concepts have exactly one [700000071000036103|has denominator units] relationship',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 700000071000036103           AND isKindOf_cr(sourceId, 700000041000036105)           GROUP BY sourceid           HAVING count(*) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12b DNF [700000091000036104|has numerator units] relationships only have destinationIds that are types of 30428011000036107|unit of measure (excluding types of Composite Unit of Measure)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeid = 700000091000036104           AND NOT isKindOf_cr(destinationid, 30428011000036107)           AND NOT isKindOf_cr(destinationid, 700000041000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13b DNF [700000091000036104|has numerator units] relationships only have sourceIds that are types of 700000041000036105|composite unit of measure',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeid = 700000091000036104           AND NOT isKindOf_cr(sourceid, 700000041000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14b DNF All Composite Unit of Measure concepts have exactly one [700000091000036104|has numerator units] relationship',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE typeId = 700000091000036104           AND isKindOf_cr(sourceId, 700000041000036105)           GROUP BY sourceid           HAVING count(*) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15b DNF Composite Unit of Measure relationships are only 700000091000036104|has numerator units OR 700000071000036103|has denominator units OR 116680003|Is a relationships',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceid, 700000041000036105)           AND NOT (typeid = 700000091000036104           OR typeid = 700000071000036103           OR typeid = 116680003);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16b DNF All Composite Unit of Measure relationships are ungrouped (group = 0)',
	null,
	null
FROM <PROSPECTIVE>.relationship_active           WHERE isKindOf_cr(sourceid, 700000041000036105)           AND relationshipgroup != 0;

