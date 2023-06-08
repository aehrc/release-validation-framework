/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some Medicinal Products exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30497011000036103);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All Medicinal Products are 900000000000073002|Defined',
	null,
	null
from <PROSPECTIVE>.concept_active           where isKindOf_cr(id,30497011000036103)           and not isKindOf_cr(id,30450011000036109)           and definitionStatusId != 900000000000073002;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 Medicinal Product is 900000000000073002|Defined',
	null,
	null
from <PROSPECTIVE>.concept_active           where id = 30497011000036103           and definitionStatusId != 900000000000073002;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All concepts with semantic tag (medicinal product) on AMT module are kinds of 30497011000036103|Medicinal Product (or itself)',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId)           and typeId = 900000000000003001           and SUBSTRING(term,-19,19) = '(medicinal product)'           and NOT isKindOf_cr(conceptId,30497011000036103)                                         and moduleid = 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 All concepts that are kinds of 30497011000036103|Medicinal Product (and not kinds 30450011000036109|MPUU) have semantic tag (medicinal product)',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId,30497011000036103)           and NOT isKindOf_cr(conceptId,30450011000036109)           and typeId = 900000000000003001           and SUBSTRING(term,-19,19) != '(medicinal product)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 Concept 30497011000036103|Medicinal Product has FSN = medicinal product (medicinal product)',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000003001           and conceptId = 30497011000036103           and term != BINARY 'medicinal product (medicinal product)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Concept 30497011000036103|Medicinal Product has SYN = medicinal product or MP - medicinal product',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000013009           and conceptId = 30497011000036103            and term != BINARY 'medicinal product'                                         and term != BINARY 'MP - medicinal product';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 Concept 30497011000036103|Medicinal Product has SYN = medicinal product or MP - medicinal product',
	null,
	null
select get_cr_ADRS_PT(30497011000036103) = BINARY 'medicinal product';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 has intended active ingredient (700000081000036101) relationships only have sourceId that are kinds of 30497011000036103|Medicinal Product',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000081000036101           and NOT isKindOf_cr(sourceId,30497011000036103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 has intended active ingredient relationships(700000081000036101) only have destinationId 30388011000036105|medicinal substance',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000081000036101           and NOT isKindOf_cr(destinationId,30388011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 Medicinal Products only have [700000081000036101|has intended active ingredient] OR [116680003|Is a] relationships - (except 30450011000036109|MPUU)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId,30497011000036103)           and NOT isKindOf_cr(sourceId,30450011000036109)           and typeId not in (700000081000036101,116680003);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 Every Medicinal Product has at least 1 has intended active ingredient(700000081000036101) relationship',
	null,
	null
from <PROSPECTIVE>.concept_active AS C           where id not in (select sourceId from <PROSPECTIVE>.relationship_active                where typeId = 700000081000036101)           and isKindOf_cr(id,30497011000036103)           and NOT isKindOf_cr(id,30450011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 All DNF hasIAI relationships are grouped (group != 0)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000081000036101            and relationshipgroup = 0;

