/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 0)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Medication container type reference set(1183941000168107) is populated',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active WHERE refsetId = 1183941000168107;
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 RefsetId(1183941000168107) is an active concept',
	null,
	null
FROM <PROSPECTIVE>.concept_active where id = 1183941000168107;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All active references are on module 900062011000036108|AMT Module',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_<SNAPSHOT> WHERE refsetId = 1183941000168107 AND moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 FSN is Medication container type reference set (foundation metadata concept)',
	null,
	null
FROM <PROSPECTIVE>.description_active WHERE conceptId = 1183941000168107           AND typeId = 900000000000003001           AND term = 'Medication container type reference set (foundation metadata concept)';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 PT (in ADRS) is Medication container type reference set',
	null,
	null
SELECT get_cr_ADRS_PT(1183941000168107) = 'Medication container type reference set';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 Acceptable synonym (in ADRS) is Medicine container type reference set',
	null,
	null
from <PROSPECTIVE>.crefset_active                                         where refsetId = 32570271000036106 and value1 = 900000000000549004                                          and referencedComponentId in (SELECT id from <PROSPECTIVE>.description_active where term = 'Medicine container type reference set');

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 All active members are descendents of 30446011000036105 |container type (AU qualifier)|',
	null,
	null
from <PROSPECTIVE>.concept_active           Where isActiveMemberOf_cr_refset(id, 1183941000168107)           and NOT isDescendentOf_cr(id, 30446011000036105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 All descendents of 30446011000036105 |container type (AU qualifier)| are active members',
	null,
	null
from <PROSPECTIVE>.concept_active           Where NOT isActiveMemberOf_cr_refset(id, 1183941000168107)           and isDescendentOf_cr(id, 30446011000036105);

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 IS A child of 32570701000036108 | Reference sets for Medications',
	null,
	null
select isChildOf_cr(1183941000168107, 32570701000036108);
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 IS A child of 446609009 | Simple type reference set',
	null,
	null
select isChildOf_cr(1183941000168107, 446609009);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 Only has parents 32570701000036108 | Reference sets for Medications  AND 446609009 | Simple type reference set',
	null,
	null
from <PROSPECTIVE>.relationship_active           where sourceId = 1183941000168107                                         and typeId = 116680003           and destinationId not in (32570701000036108,446609009);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 No duplicate active references (referencedComponentId is unique within reference set)',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active            WHERE refsetId = 1183941000168107           GROUP BY referencedComponentId           HAVING count(*) > 1;

