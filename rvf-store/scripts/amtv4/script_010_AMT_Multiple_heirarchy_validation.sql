insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 No AMT Australian concepts are descendents of more than 1 top level concept (Qualifier, Product, Substance)',
	null,
	null
from <PROSPECTIVE>.transitiveclosuretable           where destinationId in (30506011000036107,30515011000036103,30344011000036106)           group by sourceId           having count(destinationId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 No AMT Qualifier concepts are descendents of more than 1 top level qualifier type',
	null,
	null
from <PROSPECTIVE>.transitiveclosuretable           where destinationId in (30446011000036105,30383011000036100,30428011000036107,700000011000036109)           group by sourceId           having count(destinationId) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 Only 4 Top level qualifier types in AMT',
	null,
	null
from <PROSPECTIVE>.concept_active where isChildOf_cr(id, 30515011000036103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 4 Top level qualifier types in AMT, are the Container, Form, UoM, UoU',
	null,
	null
from <PROSPECTIVE>.concept_active           where isChildOf_cr(id, 30515011000036103)           and id not in (30446011000036105,30383011000036100,30428011000036107,700000011000036109);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 No AMT Product concepts are descendents of both MP and MPP',
	null,
	null
from <PROSPECTIVE>.transitiveclosuretable           where destinationId in (30497011000036103,30513011000036104)           group by sourceId           having count(destinationId) > 1;

