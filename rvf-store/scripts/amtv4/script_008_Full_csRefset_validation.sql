/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 0)
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 rf2_cr_csRefset_full has been populated',
	null,
	null
from TableIsPopulated('<PROSPECTIVE>.csRefset_<FULL>');
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
from <PROSPECTIVE>.csRefset_<FULL> where not isValidUUID(id);
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
from <PROSPECTIVE>.csRefset_<FULL> where NOT isValidTimeFormat(effectiveTime);
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
from <PROSPECTIVE>.csRefset_<FULL> where active != 1 AND active != 0;
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
FROM <PROSPECTIVE>.csRefset_<FULL> WHERE NOT isValidModuleId(moduleId);
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
FROM <PROSPECTIVE>.csRefset_<FULL> WHERE NOT isValidRefsetId_cr(refsetId);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 referencedComponentId is a valid ComponentId (including stated and DNF rel tables)',
	null,
	null
FROM <PROSPECTIVE>.csRefset_<FULL>            WHERE NOT isValidComponentId_cr(referencedComponentId)           AND NOT Stated_isValidComponentId_cr(referencedComponentId);
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
from <PROSPECTIVE>.csRefset_<FULL> group by id, effectiveTime having count(*) > 1;
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09a refSetId will not change between two rows with the same id, in other words they are immutable',
	null,
	null
FROM <PROSPECTIVE>.csRefset_<FULL>            GROUP BY id HAVING COUNT(distinct referencedComponentId) > 1;
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09b referencedComponentId fields will not change between two rows with the same id, in other words they are immutable',
	null,
	null
FROM <PROSPECTIVE>.csRefset_<FULL>            GROUP BY id HAVING COUNT(distinct refSetId) > 1;
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
from <PROSPECTIVE>.csRefset_<FULL> as refset where moduleId not in (select id from <PROSPECTIVE>.concept_<FULL>);
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
from <PROSPECTIVE>.csRefset_<FULL> as refset where refsetId not in (select id from <PROSPECTIVE>.concept_<FULL>);
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
from <PROSPECTIVE>.csRefset_<FULL> where NOT isValidComponentId_cr(valueId);
*/

/* SKIPPED_TEST
	: Query references a non-simple refset. Likely need to refactor this query

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 StringValue is not null and contains at least 1 character',
	null,
	null
from <PROSPECTIVE>.csRefset_<FULL> where StringValue is null or CHAR_LENGTH(StringValue) < 1;
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 All currently active references are unique within rf2_cr_csRefset_active',
	null,
	null
from <PROSPECTIVE>.csrefset_active           group by refsetId,referencedComponentId,valueId,StringValue           having count(1) > 1;

