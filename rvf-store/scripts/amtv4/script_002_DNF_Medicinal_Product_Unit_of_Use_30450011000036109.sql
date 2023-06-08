/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some Medicinal Product Unit of Use exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30450011000036109);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All Medicinal Product Unit of Use are 900000000000073002|Defined or 900000000000074008|Primitive',
	null,
	null
from <PROSPECTIVE>.concept_active           where isKindOf_cr(id,30450011000036109)           and definitionStatusId not in (900000000000073002,900000000000074008);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 Medicinal Product Unit of Use is 900000000000073002|Defined',
	null,
	null
from <PROSPECTIVE>.concept_active           where id = 30450011000036109           and definitionStatusId != 900000000000073002;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All concepts with semantic tag (medicinal product unit of use) are kinds of 30450011000036109|Medicinal Product Unit of Use',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId)           and typeId = 900000000000003001           and SUBSTRING(term,-31,31) = '(medicinal product unit of use)'           and NOT isKindOf_cr(conceptId,30450011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All concepts that are Kinds of 30450011000036109|MPUU (but not 30425011000036101 |trade product unit of use|) have semantic tag (medicinal product unit of use)',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId,30450011000036109)           and NOT isKindOf_cr(conceptId,30425011000036101)           and typeId = 900000000000003001           and SUBSTRING(term,-31,31) != '(medicinal product unit of use)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 30450011000036109|MPUU has FSN = medicinal product unit of use (medicinal product unit of use)',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000003001           and conceptId = 30450011000036109           and term != BINARY 'medicinal product unit of use (medicinal product unit of use)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 Concept 30450011000036109|MPUU has SYN = medicinal product unit of use or MPUU - medicinal product unit of use',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000013009           and conceptId = 30450011000036109            and term != BINARY 'medicinal product unit of use'           and term != BINARY 'MPUU - medicinal product unit of use';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Concept 30450011000036109|MPUU has SYN = medicinal product unit of use or MPUU - medicinal product unit of use',
	null,
	null
select get_cr_ADRS_PT(30450011000036109) = BINARY 'medicinal product unit of use';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 has intended active ingredient (700000081000036101) relationships only have sourceId that are kinds of 30497011000036103|Medicinal Product',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000081000036101           and NOT isKindOf_cr(sourceId,30497011000036103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 has intended active ingredient relationships(700000081000036101) only have destinationId 30388011000036105|medicinal substance',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000081000036101           and NOT isKindOf_cr(destinationId,30388011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 Every MPUU has at least 1 has intended active ingredient(700000081000036101) relationship',
	null,
	null
from <PROSPECTIVE>.concept_active AS C           where isKindOf_cr(id,30450011000036109)           and id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 700000081000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 has Australian BoSS (30364011000036101) relationships only have sourceId that are kinds of 30450011000036109|MPUU',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30364011000036101           and NOT isKindOf_cr(sourceId,30450011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 has Australian BoSS (30364011000036101) relationships only have destinationId 30388011000036105|medicinal substance',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30364011000036101           and NOT isKindOf_cr(destinationId,30388011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 Every has Australian BoSS (30364011000036101) relationship IS referenced in the Strength reference set(700000111000036105)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30364011000036101           and id not in (select referencedComponentId from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000111000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 Every has Australian BoSS (30364011000036101) relationship is grouped',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30364011000036101           and relationshipGroup = 0;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 Every has Australian BoSS (30364011000036101) relationship grouped with an has intended active ingredient(700000081000036101)',
	null,
	null
from <PROSPECTIVE>.relationship_active AS BoSS           where typeId = 30364011000036101           and relationshipGroup not in (select relationshipGroup from <PROSPECTIVE>.relationship_active AS IAI                    where IAI.sourceId = BoSS.sourceId);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 Only [has Australian BoSS (30364011000036101)] always grouped',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where isKindOf_cr(sourceId, 30450011000036109)                                         and relationshipGroup = 0                                         and typeId = 30364011000036101;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 Only [has intended active ingredient(700000081000036101)] always grouped',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where isKindOf_cr(sourceId, 30450011000036109)                                         and relationshipGroup = 0                                         and typeId = 700000081000036101;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 Only [has unit of use(30548011000036101)] always grouped',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where isKindOf_cr(sourceId, 30450011000036109)                                         and relationshipGroup = 0                                         and typeId = 30548011000036101;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'18 has manufactured dose form (30523011000036108) relationships only have sourceId that are kinds of 30450011000036109|MPUU',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30523011000036108           and NOT isKindOf_cr(sourceId,30450011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'19 has manufactured dose form relationships(30523011000036108) only have destinationId 30383011000036100|form ',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30523011000036108           and NOT isKindOf_cr(destinationId,30383011000036100);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'20 Every MPUU has at least 1 has manufactured dose form(30523011000036108) relationship',
	null,
	null
from <PROSPECTIVE>.concept_active AS C           where id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 30523011000036108)           and isKindOf_cr(id,30450011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'21 Every MPUU has Exactly 1 has manufactured dose form(30523011000036108) relationship',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30523011000036108           group by sourceId           having count(typeId) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'21 has manufactured dose form(30523011000036108) relationship never grouped',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where isKindOf_cr(sourceId, 30450011000036109)                                         and relationshipGroup != 0                                         and typeId = 30523011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'22 has unit of use (30548011000036101) relationships only have sourceId that are kinds of 30450011000036109|MPUU',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30548011000036101           and NOT isKindOf_cr(sourceId,30450011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'23 has unit of use relationships(30548011000036101) only have destinationId 700000011000036109|unit of use  ',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30548011000036101           and NOT isKindOf_cr(destinationId,700000011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'24 Every Kind of MPUU has at least 1 has unit of use(30548011000036101) relationship',
	null,
	null
from <PROSPECTIVE>.concept_active AS C           where isKindOf_cr(id,30450011000036109)           and id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 30548011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'25 Every MPUU has Exactly 1 has unit of use(30548011000036101) relationship',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30548011000036101           group by sourceId           having count(typeId) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'26 Every has unit of use(30548011000036101) relationship IS referenced in the Unit of use size reference set(700000141000036106) - Except 30450011000036109|MPUU',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30548011000036101           and sourceId not in (30450011000036109,30425011000036101)           and id not in (select referencedComponentId from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000141000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'27 ALL Medicinal Product Unit of Use descendants only have [700000081000036101|has intended active ingredient]                   OR [116680003|Is a]                    OR [30364011000036101|has Australian BoSS]                    OR [30523011000036108|has manufactured dose form]                    OR [30548011000036101|has unit of use] relationships',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId,30450011000036109)           and typeId not in (700000081000036101,116680003,30364011000036101,30523011000036108,30548011000036101);

