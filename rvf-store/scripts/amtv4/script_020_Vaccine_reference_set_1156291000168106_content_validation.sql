/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 0)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Vaccine reference set(1156291000168106) is populated',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active WHERE refsetId = 1156291000168106;
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 RefsetId(1156291000168106) is an active concept',
	null,
	null
FROM <PROSPECTIVE>.concept_active where id = 1156291000168106;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All active references are from module 900062011000036108|AMT Module|',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_<SNAPSHOT> WHERE refsetId = 1156291000168106 AND moduleId != 900062011000036108;

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 FSN is Australian vaccine reference set (foundation metadata concept)',
	null,
	null
SELECT get_cr_FSN(1156291000168106) = 'Australian vaccine reference set (foundation metadata concept)';
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 PT (in ADRS) is Vaccine reference set',
	null,
	null
SELECT get_cr_ADRS_PT(1156291000168106) = 'Vaccine reference set';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 All members are types of 30506011000036107|Australian Product',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active WHERE refsetId = 1156291000168106                                         and not isDescendentOf_cr(referencedComponentId, 30506011000036107);

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 IS A child of 446609009 | Simple type reference set',
	null,
	null
select isChildOf_cr(1156291000168106, 446609009);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 No duplicate active references (referencedComponentId is unique within reference set)',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active            WHERE refsetId = 1156291000168106           GROUP BY referencedComponentId           HAVING count(*) > 1;

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 2)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 Only 2 descriptions exist for the reference set',
	null,
	null
FROM <PROSPECTIVE>.description_active WHERE conceptId = 1156291000168106;
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 20180630)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 Reference set debut date is 20180630',
	null,
	null
from <PROSPECTIVE>.simplerefset_<FULL>           where refsetId = 1156291000168106 limit 1;
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 0)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 All debut members are active',
	null,
	null
from <PROSPECTIVE>.simplerefset_<FULL>           where refsetId = 1156291000168106           and active = 0           and effectiveTime = 20180630;
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 All Australian products with containing ‘antigen’, ‘toxoid’, ‘virus’ vaccine, like strain or serogroup in a term are included (excluding skin(tests) and diluent)',
	null,
	null
from <PROSPECTIVE>.description_active                                         where isKindOf_cr(conceptId, 30506011000036107)                                         and term RLIKE '(antigen|toxoid|virus|vaccine|serogroup|like strain)'                                         and term not RLIKE 'skin|diluent'                                         and conceptId not in (1599991000168105, 1600021000168106, 1600041000168100, 1600051000168103, 1600071000168107, 1600081000168105)                                         and conceptId not in (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 All MPPs and subtypes containing ‘antigen’, ‘toxoid’, ‘virus’ vaccine, like strain, serogroup or tozinameran in a term are included (excluding skin(tests); diulent allowed)',
	null,
	null
from <PROSPECTIVE>.description_active                                         where isKindOf_cr(conceptId, 30513011000036104)                                         and term RLIKE '(antigen|toxoid|virus|vaccine|serogroup|like strain|tozinameran)'                                         and term not RLIKE 'skin'                                         and conceptId not in (1600041000168100, 1600051000168103, 1600071000168107, 1600081000168105)                                         and conceptId not in (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 Every member contains at least ‘antigen’, ‘toxoid’, ‘virus’ vaccine, like strain, serogroup or tozinameran in a term',
	null,
	null
from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106                                         and referencedComponentId not in (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 929360021000036102)                                         and referencedComponentId not in (select distinct conceptId from <PROSPECTIVE>.description_active where term RLIKE '(antigen|toxoid|virus|vaccine|serogroup|like strain|tozinameran)');

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 All descendents of MP members, are also members of Vaccine refset',
	null,
	null
from <PROSPECTIVE>.transitiveclosuretable                                          where destinationId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 929360061000036106)                                         and destinationId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and sourceId NOT IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 All descendents of MPP members, are also members of Vaccine refset',
	null,
	null
from <PROSPECTIVE>.transitiveclosuretable                                          where destinationId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 929360081000036101)                                         and destinationId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and sourceId NOT IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 All ancestors of TPUU members, are also members of Vaccine refset',
	null,
	null
from <PROSPECTIVE>.transitiveclosuretable                                          where sourceId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 929360031000036100)                                         and sourceId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and destinationId NOT IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and destinationId NOT IN (30506011000036107,30561011000036101,30425011000036101,30560011000036108,30450011000036109,30497011000036103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'18 All ancestors of CTPP members, are also members of Vaccine refset',
	null,
	null
from <PROSPECTIVE>.transitiveclosuretable                                          where sourceId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 929360051000036108)                                         and sourceId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and destinationId NOT IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and destinationId NOT IN (30537011000036101,30404011000036106,30506011000036107,30513011000036104,30561011000036101);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'19 All TPs of members, are also members of Vaccine refset',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where destinationId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 929360021000036102)                                         and sourceId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and destinationId NOT IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'20 All products whose TP is a member, are also members of Vaccine refset (excluding inert diluents)',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where destinationId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 929360021000036102)                                         and destinationId IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and sourceId NOT IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1156291000168106)                                         and sourceID not in (select sourceId from <PROSPECTIVE>.relationship_active where typeId = 700000081000036101 and destinationId = 920012011000036105);

