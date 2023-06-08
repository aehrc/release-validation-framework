insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Slashes ARE surrounded by spaces in FSNs (when separating an alpha and digit) for MPUU FSNs',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveMemberOf_cr_refset(conceptId,929360071000036103)           and typeId = 900000000000003001           and term REGEXP '.+ [[:alpha:]]+/[[:digit:]]+ .+';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 Slashes are NOT surrounded by spaces in synonyms EVER for MPUU synonyms',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveMemberOf_cr_refset(conceptId,929360071000036103)           and typeId =  900000000000013009           and term REGEXP '.+ / .+';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 Slashes dont have a space to the left in synonyms EVER for MPUU synonyms',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveMemberOf_cr_refset(conceptId,929360071000036103)           and typeId =  900000000000013009           and term REGEXP '.+ /.+' AND NOT term REGEXP '.+ / .+';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 Slashes dont have a space to the right in synonyms EVER for MPUU synonyms',
	null,
	null
from <PROSPECTIVE>.description_active           where isActiveMemberOf_cr_refset(conceptId,929360071000036103)           and typeId =  900000000000013009           and term REGEXP '.+/ .+' AND NOT term REGEXP '.+ / .+';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 Initial character case insensitive AU terms are sentence case (excluding AMT)',
	null,
	null
from <PROSPECTIVE>.description_active                                         where casesignificanceid = 900000000000020002                                         and conceptId in (select id from <PROSPECTIVE>.concept_active)                                         and BINARY SUBSTRING(term,1,1) != BINARY upper(SUBSTRING(term,1,1))           and moduleId in (161771000036108,32506021000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 Case insensitive AU terms are sentence case (excluding AMT)',
	null,
	null
from <PROSPECTIVE>.description_active                                         where casesignificanceid = 900000000000448009                                         and conceptId in (select id from <PROSPECTIVE>.concept_active)                                         and BINARY SUBSTRING(term,1,1) != BINARY upper(SUBSTRING(term,1,1))           and moduleId in (161771000036108,32506021000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'07 Case insensitive AU terms dont contain upper case letters after first',
	null,
	null
from <PROSPECTIVE>.description_active                                         where caseSignificanceId = 900000000000448009                                         and conceptId in (select id from <PROSPECTIVE>.concept_active)                                         and BINARY SUBSTRING(term,2) != BINARY lower(SUBSTRING(term,2))           and moduleId in (161771000036108,900062011000036108,32506021000036107);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'08 No Rogue _foo_ are present',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT> where term RLIKE '_foo_';

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 No Rogue _NEVER PUBLISHED_ are present',
	null,
	null
from <PROSPECTIVE>.description_<SNAPSHOT> where term RLIKE '_NEVER PUBLISHED_';

