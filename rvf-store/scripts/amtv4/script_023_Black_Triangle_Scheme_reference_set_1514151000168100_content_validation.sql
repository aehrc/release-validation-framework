/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 0)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Australian Schedule 8 controlled trade medications(1514151000168100) is populated',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active WHERE refsetId = 1514151000168100;
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 RefsetId(1514151000168100) is an active concept',
	null,
	null
FROM <PROSPECTIVE>.concept_active where id = 1514151000168100;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All active references are on module  900062011000036108 |AMT module|',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active WHERE refsetId = 1514151000168100 AND moduleId !=  900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 FSN is Australian Black Triangle Scheme reference set (foundation metadata concept)',
	null,
	null
FROM <PROSPECTIVE>.description_active WHERE conceptId = 1514151000168100           AND typeId = 900000000000003001           AND term = 'Australian Black Triangle Scheme reference set (foundation metadata concept)';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 PT (in ADRS) is Black Triangle Scheme reference set',
	null,
	null
SELECT get_cr_ADRS_PT(1514151000168100) = 'Black Triangle Scheme reference set';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 All active members are TPUU or CTPP concepts',
	null,
	null
from <PROSPECTIVE>.concept_active           Where isActiveMemberOf_cr_refset(id, 1514151000168100)           and NOT (isActiveMemberOf_cr_refset(id, 929360031000036100)                           OR isActiveMemberOf_cr_refset(id, 929360051000036108)                           );

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 IS A child of 32570701000036108 | Reference sets for Medications',
	null,
	null
select isChildOf_cr(1514151000168100, 32570701000036108);
*/

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 IS A child of 446609009 | Simple type reference set',
	null,
	null
select isChildOf_cr(1514151000168100, 446609009);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 Only has parents 32570701000036108 | Reference sets for Medications AND 446609009 | Simple type reference set',
	null,
	null
from <PROSPECTIVE>.relationship_active           where sourceId = 1514151000168100                                         and typeId = 116680003           and destinationId not in (32570701000036108,446609009);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 No duplicate active references (referencedComponentId is unique within reference set)',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active            WHERE refsetId = 1514151000168100           GROUP BY referencedComponentId           HAVING count(*) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 All CTPP Members are BTS products',
	null,
	null
from <PROSPECTIVE>.irefset_active                                         where refsetId = 11000168105           and isActiveMemberOf_cr_refset(referencedComponentId, 1514151000168100)                                         and value1 in (select distinct TGA.ARTGID                                                         from TGA_Data.tga_feed AS TGA                                                        where TGA.BlackTriangle != 'Y');

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 All BTS products are Members',
	null,
	null
from <PROSPECTIVE>.irefset_active                                         where value1 in (select distinct TGA.ARTGID from TGA_Data.tga_feed AS TGA where BlackTriangle = 'Y')                                         and not isActiveMemberOf_cr_refset(referencedComponentId, 1514151000168100);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 All TPPus of member CTPPs are also member',
	null,
	null
from <PROSPECTIVE>.relationship_active                                          where typeId = 30409011000036107 and isActiveMemberOf_cr_refset(sourceId, 1514151000168100)                                          and NOT isActiveMemberOf_cr_refset(destinationId, 1514151000168100);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 All CTPPs that have TPUUs that are members are also members',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where typeId = 30409011000036107                                         and NOT isActiveMemberOf_cr_refset(sourceId, 1514151000168100)                                         and isActiveMemberOf_cr_refset(sourceId, 929360051000036108)                                         and isActiveMemberOf_cr_refset(destinationId, 1514151000168100);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 BTS Products not in AMT',
	null,
	null
from TGA_Data.tga_feed AS TGA where TGA.BlackTriangle = 'Y'                                          and TGA.ARTGID not in (select value1 from <PROSPECTIVE>.irefset_active where refsetId = 11000168105)                                         and TGA.ARTGID not in (325682,287446);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15x ARTGID 287446 - exception monitoring See AA-58336',
	null,
	null
from <PROSPECTIVE>.description_active                                         where (term RLIKE 'fluorouracil' AND term RLIKE 'salicyl') OR term RLIKE 'ACTIKERALL'                                         UNION                                         select referencedComponentId, get_cr_FSN(referencedComponentId) from <PROSPECTIVE>.irefset_active where refsetId = 11000168105 AND value1 in (325682,287446);

