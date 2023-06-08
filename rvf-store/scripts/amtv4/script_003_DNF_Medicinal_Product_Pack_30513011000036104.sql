/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some Medicinal Product Pack exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30513011000036104);
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 99.8)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 DNF 99.8% Medicinal Product Pack are 900000000000073002|Defined|',
	null,
	null
select TRUNCATE(get_cr_PercentDefined(929360081000036101),1);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 Medicinal Product Pack is 900000000000073002|Defined',
	null,
	null
from <PROSPECTIVE>.concept_active           where id = 30513011000036104           and definitionStatusId != 900000000000073002;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All concepts with semantic tag (Medicinal Product Pack) are Descendents of 30513011000036104|Medicinal Product Pack (or itself)',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveConcept_cr(conceptId)           and typeId = 900000000000003001           and SUBSTRING(term,-24,24) = BINARY '(medicinal product pack)'           and NOT isKindOf_cr(conceptId,30513011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All concepts that are Descendents of 30513011000036104|medicinal product pack have semantic tag (Medicinal Product Pack) - (except 30404011000036106|TPUU descendants)',
	null,
	null
from <PROSPECTIVE>.description_active           where isKindOf_cr(conceptId,30513011000036104)           and NOT isKindOf_cr(conceptId,30404011000036106)           and typeId = 900000000000003001           and SUBSTRING(term,-24,24) != BINARY '(medicinal product pack)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Concept 30513011000036104|MPUU has FSN = medicinal product pack (medicinal product pack)',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000003001           and conceptId = 30513011000036104           and term != BINARY 'medicinal product pack (medicinal product pack)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06a Concept 30513011000036104|MPUU has SYN = medicinal product pack or MPP - medicinal product pack',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000013009           and conceptId = 30513011000036104            and term != BINARY 'medicinal product pack'           and term != BINARY 'MPP - medicinal product pack';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06b Concept 30513011000036104|MPUU has SYN = medicinal product pack or MPP - medicinal product pack',
	null,
	null
select get_cr_ADRS_PT(30513011000036104) = BINARY 'medicinal product pack';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 has MPUU (30348011000036104) relationships only have sourceId that are descendants of 30513011000036104|MPP (or MPP itself)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30348011000036104           and NOT isKindOf_cr(sourceId,30513011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 has MPUU (30348011000036104) only have destinationId 30450011000036109|MPUU',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30348011000036104           and NOT isKindOf_cr(destinationId,30450011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 Every MPP has at least 1 has MPUU (30348011000036104) relationship (except 30404011000036106 [trade product pack] descendants)',
	null,
	null
from <PROSPECTIVE>.concept_active AS C           where isKindOf_cr(id,30513011000036104)           and id not in (select sourceId from <PROSPECTIVE>.relationship_active                 where typeId = 30348011000036104)           and NOT isKindOf_cr(id,30404011000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 Every has MPUU (30348011000036104) relationship IS referenced in the Unit of use quantity reference set(700000131000036101) - Except 30513011000036104|MPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30348011000036104           and sourceId != 30513011000036104           and id not in (select referencedComponentId from <PROSPECTIVE>.ccsrefset_active where refsetId = 700000131000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 has subpack (30454011000036104) relationships only have sourceId that are kinds of 30513011000036104|MPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           and NOT isKindOf_cr(sourceId,30513011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 has subpack (30454011000036104) relationships only have destinationId that are kinds of 30513011000036104|MPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           and NOT isKindOf_cr(destinationId,30513011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 If sourceId is kind of 30513011000036104|MPP, has subpack (30454011000036104) only destinationId that are also kinds of 30513011000036104|MPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           and isKindOf_cr(sourceId,30513011000036104)           and NOT isKindOf_cr(sourceId,30404011000036106)           and isKindOf_cr(destinationId,30404011000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 If destinationId is a 30513011000036104|MPP, all has subpack (30454011000036104) sourceId are either 30513011000036104|MPP or 30404011000036106|TPP (ie. not CTPP)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           and isKindOf_cr(destinationId,30513011000036104)           and NOT isKindOf_cr(destinationId,30404011000036106)           and isKindOf_cr(sourceId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 If destinationId is kind of 30513011000036104|MPP (and Not Kind of TPP|30404011000036106), all has subpack (30454011000036104) sourceId are also kinds of 30513011000036104|MPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           and isKindOf_cr(destinationId,30513011000036104)           and NOT isKindOf_cr(destinationId,30404011000036106)           and NOT isKindOf_cr(sourceId,30513011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 If source is kind of 30513011000036104|MPP (and Not Kind of TPP|30404011000036106), all has subpack (30454011000036104) destinationId are also kinds of 30513011000036104|MPP (and not TPP)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           and isKindOf_cr(sourceId,30513011000036104)           and NOT isKindOf_cr(sourceId,30404011000036106)           and NOT isKindOf_cr(destinationId,30513011000036104)           and isKindOf_cr(destinationId,30404011000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 Maximum of 1 has subpack (30454011000036104) for all kinds of 30513011000036104|MPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           group by sourceId           having count(*) != 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 Every has subpack (30454011000036104) relationship IS referenced in the Subpack quantity reference set(700000121000036103)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 30454011000036104           and id not in (select referencedComponentId from <PROSPECTIVE>.ccirefset_active where refsetId = 700000121000036103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 has component pack (700000061000036106) relationships only have sourceId that are kinds of 30513011000036104|MPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000061000036106           and NOT isKindOf_cr(sourceId,30513011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'18 has component pack (700000061000036106) relationships only have destinationId that are kinds of 30513011000036104|MPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000061000036106           and NOT isKindOf_cr(destinationId,30513011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'19 If sourceId is kind of 30513011000036104|MPP (NOT TPP), has component pack (700000061000036106) only destinationId that are also kinds of 30513011000036104|MPP (NOT TPP)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000061000036106           and isKindOf_cr(sourceId,30513011000036104)           and NOT isKindOf_cr(sourceId,30404011000036106)           and isKindOf_cr(destinationId,30404011000036106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'20 If destinationId is kind of 30513011000036104|MPP, all has component pack (700000061000036106) sourceId are also kinds of 30513011000036104|MPP (but not CTPP)',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000061000036106           and isKindOf_cr(destinationId,30513011000036104)           and NOT isKindOf_cr(destinationId,30404011000036106)           and isKindOf_cr(sourceId,30537011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'21 30513011000036104|MPPs only have   OR [116680003|Is a]                    OR [30348011000036104|has MPUU]                    OR [700000061000036106|has component pack]                    OR [30454011000036104|has subpack] relationships - Excluging 30404011000036106|TPP',
	null,
	null
from <PROSPECTIVE>.relationship_active           where isKindOf_cr(sourceId,30513011000036104)           and not isKindOf_cr(sourceId,30404011000036106)           and typeId not in (116680003,30348011000036104,700000061000036106,30454011000036104);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'22 DNF 30513011000036104|MPPs with 700000061000036106|hasComponentPack relationships, have at least 2',
	null,
	null
from <PROSPECTIVE>.relationship_active           where typeId = 700000061000036106           and isDescendentOf_cr(sourceId,30513011000036104) and not isDescendentOf_cr(sourceId,30404011000036106)           group by sourceId           having count(id) < 2;

