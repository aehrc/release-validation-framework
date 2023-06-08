insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 All AMT concepts with chlortalidone FSN, have more than one synonym',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000013009           and conceptId in (                 select conceptId from <PROSPECTIVE>.description_active                 where Binary term RLIKE Binary '[[:<:]]chlortalidone[[:>:]]' and typeId = 900000000000003001                 and conceptId in (select id from <PROSPECTIVE>.concept_active)                 and moduleId = 900062011000036108                 )           group by conceptId           having count(*) = 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 No AMT Preferred terms contain the chlorthalidone (Excluding TPs)',
	null,
	null
from <PROSPECTIVE>.description_active           where Binary term RLIKE Binary '[[:<:]]chlortalidone[[:>:]]' and typeId = 900000000000003001           and conceptId in (select id from <PROSPECTIVE>.concept_active)           and moduleId = 900062011000036108           and BINARY get_cr_ADRS_PT(conceptId) RLIKE '[[:<:]]chlorthalidone[[:>:]]';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 No AMT non-PT synonym contains the chlortalidone (Excluding TPs)',
	null,
	null
from <PROSPECTIVE>.description_active           where typeId = 900000000000013009           and conceptId in (select id from <PROSPECTIVE>.concept_active)           and conceptId in (                 select conceptId from <PROSPECTIVE>.description_active                 where Binary term RLIKE Binary '[[:<:]]chlortalidone[[:>:]]' and typeId = 900000000000003001                 and conceptId in (select id from <PROSPECTIVE>.concept_active)                 and moduleId = 900062011000036108                 )           and id not in (select referencedComponentId from <PROSPECTIVE>.langrefset_active where refsetId = 32570271000036106 and value1 = 900000000000548007)           and BINARY term RLIKE '[[:<:]]chlortalidone[[:>:]]';

