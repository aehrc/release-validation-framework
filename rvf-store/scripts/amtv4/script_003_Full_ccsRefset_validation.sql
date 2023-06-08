/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 0)
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 rf2_cr_ccsRefset_full has been populated',
	null,
	null
from TableIsPopulated('<PROSPECTIVE>.ccsRefset_<FULL>');
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 id field is a valid 128 bit unsigned integer/UUID',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> where not isValidUUID(id);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 effectiveTime is valid Timeformat',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> where NOT isValidTimeFormat(effectiveTime);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 active is boolean value',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> where active != 1 AND active != 0;
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 moduleId is a descendant of 900000000000443000|Module| within the metadata hierarchy',
	null,
	null
FROM <PROSPECTIVE>.ccsRefset_<FULL> WHERE NOT isValidModuleId(moduleId);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 refsetId is a descendant of  900000000000455006 | Reference set | within the metadata hierarchy',
	null,
	null
FROM <PROSPECTIVE>.ccsRefset_<FULL> WHERE NOT isValidRefsetId_cr(refsetId);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 referencedComponentId is a valid ComponentId',
	null,
	null
FROM <PROSPECTIVE>.ccsRefset_<FULL> WHERE NOT isValidComponentId_cr(referencedComponentId);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 Only one reference set member record with the same id field will be current at any point in time',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> group by id, effectiveTime having count(*) > 1;
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 refSetId and referencedComponentId fields will not change between two rows with the same id, in other words they are immutable',
	null,
	null
FROM <PROSPECTIVE>.ccsRefset_<FULL>            GROUP BY id HAVING COUNT(distinct refSetId,referencedComponentId) > 1;
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 moduleId is a ConceptId (referential integrity)',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> as refset where moduleId not in (select id from <PROSPECTIVE>.concept_<FULL>);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 refsetId is a ConceptId (referential integrity)',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> as refset where refsetId not in (select id from <PROSPECTIVE>.concept_<FULL>);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 value1 is a valid ComponentId',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> where NOT isValidComponentId_cr(value1);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 value2 is a valid ComponentId',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> where NOT isValidComponentId_cr(value2);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 value3 is not null and contains at least 1 character',
	null,
	null
from <PROSPECTIVE>.ccsRefset_<FULL> where value3 is null or CHAR_LENGTH(value3) < 1;
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 All currently active references are unique within rf2_cr_ccsrefset_active',
	null,
	null
from <PROSPECTIVE>.ccsrefset_active           group by refsetId,referencedComponentId,value1,value2,value3           having count(1) > 1;

