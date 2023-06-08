/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 0)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Medicinal product reference set (929360061000036106) is populated',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active WHERE refsetId = 929360061000036106;
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 RefsetId(929360061000036106) is an active concept',
	null,
	null
FROM <PROSPECTIVE>.concept_active where id = 929360061000036106;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All active references are from module 900062011000036108 [Australian Medicines Terminology module]',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_<SNAPSHOT> WHERE refsetId = 929360061000036106 AND moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 FSN is Medicinal product reference set (foundation metadata concept)',
	null,
	null
FROM <PROSPECTIVE>.description_active WHERE conceptId = 929360061000036106           AND typeId = 900000000000003001           AND term = 'Medicinal product reference set (foundation metadata concept)' AND id != 965507121000036119;

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 PT (in ADRS) is Medicinal product reference set',
	null,
	null
SELECT get_cr_ADRS_PT(929360061000036106) = 'Medicinal product reference set';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 Only active descriptions are the expected PT and FSN',
	null,
	null
FROM <PROSPECTIVE>.description_active WHERE conceptId = 929360061000036106           AND term != 'Medicinal product reference set (foundation metadata concept)'           AND term != 'Medicinal product reference set'           AND term != 'MP (medicinal product) reference set';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Foundation refset IS A child of 446609009 | Simple type reference set',
	null,
	null
select isChildOf_cr(929360061000036106, 446609009);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 Foundation refset only has parent 446609009 | Simple type reference set',
	null,
	null
from <PROSPECTIVE>.relationship_active           where sourceId = 929360061000036106                                         and typeId = 116680003           and destinationId not in (446609009);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 Only Contains Active descendants of Medicinal product',
	null,
	null
from <PROSPECTIVE>.simplerefset_active           where refsetId = 929360061000036106           and NOT isDescendentOf_cr(referencedComponentid,30497011000036103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 Does not contain any descendants of the other 6 boxes (non-MP)',
	null,
	null
from <PROSPECTIVE>.simplerefset_active           where refsetId = 929360061000036106           and (            isDescendentOf_cr(referencedComponentid,30513011000036104)            OR            isDescendentOf_cr(referencedComponentid,30450011000036109)            OR            isDescendentOf_cr(referencedComponentid,30560011000036108)            OR            isDescendentOf_cr(referencedComponentid,30404011000036106)            OR            isDescendentOf_cr(referencedComponentid,30425011000036101)            OR            isDescendentOf_cr(referencedComponentid,30537011000036101)            );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 Contains all Active Medicinal products',
	null,
	null
from <PROSPECTIVE>.concept_active           where not isActiveMemberOf_cr_refset(id,929360061000036106)           and isDescendentOf_cr(id,30497011000036103)           and NOT isKindOf_cr(id,30450011000036109);

