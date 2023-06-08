/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Some Trade Products exist',
	null,
	null
from <PROSPECTIVE>.concept_active where id in (select sourceid from <PROSPECTIVE>.transitiveclosuretable where destinationId = 30560011000036108);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 All Trade Products are 900000000000074008|Primitive',
	null,
	null
from <PROSPECTIVE>.concept_active           where isKindOf_cr(id,30560011000036108)           and not isKindOf_cr(id,30425011000036101)           and definitionStatusId != 900000000000074008;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 Trade Product is 900000000000074008|Primitive',
	null,
	null
from <PROSPECTIVE>.concept_active           where id = 30560011000036108           and definitionStatusId != 900000000000074008;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 All concepts with semantic tag (trade product) on AMT module are kinds of 30560011000036108|Trade Product (or itself)',
	null,
	null
from <PROSPECTIVE>.description_active                                         where isActiveConcept_cr(conceptId)                                         and typeId = 900000000000003001                                         and SUBSTRING(term,-15) = '(trade product)'                                         and NOT isKindOf_cr(conceptId,30560011000036108);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 All concepts that are kinds of 30560011000036108|Trade Product (and not kinds 30425011000036101|TPUU) have semantic tag (trade product)',
	null,
	null
from <PROSPECTIVE>.description_active                                         where isKindOf_cr(conceptId,30560011000036108)                                         and NOT isKindOf_cr(conceptId,    30425011000036101)                                         and typeId = 900000000000003001                                         and BINARY SUBSTRING(term,-16) != ' (trade product)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 Concept 30560011000036108|Trade Product has FSN = trade product (trade product)',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000003001           and conceptId = 30560011000036108           and term != BINARY 'trade product (trade product)';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Concept 30560011000036108|Trade Product has SYN = trade product or MP - trade product',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000013009           and conceptId = 30560011000036108            and term != BINARY 'trade product'                                         and term != BINARY 'TP - trade product';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 Concept 30560011000036108|Trade Product has SYN = trade product or TP - trade product',
	null,
	null
select get_cr_ADRS_PT(30560011000036108) = BINARY 'trade product';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 TPs only have a relationship that is IS A 30560011000036108|TP|',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where isDescendentOf_cr(sourceId,30560011000036108)                                         and NOT isKindOf_cr(sourceId,    30425011000036101)                                         and (typeId != 116680003 OR destinationId != 30560011000036108);

