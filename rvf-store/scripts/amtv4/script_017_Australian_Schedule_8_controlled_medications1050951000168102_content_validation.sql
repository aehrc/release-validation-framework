/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result > 0)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'01 Australian Schedule 8 controlled trade medications(1050951000168102) is populated',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active WHERE refsetId = 1050951000168102;
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'02 RefsetId(1050951000168102) is an active concept',
	null,
	null
FROM <PROSPECTIVE>.concept_active where id = 1050951000168102;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'03 All active references are from module 900062011000036108|AMT module|',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active WHERE refsetId = 1050951000168102 AND moduleId != 900062011000036108;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'04 FSN is Australian Schedule 8 controlled medications reference set (foundation metadata concept)',
	null,
	null
FROM <PROSPECTIVE>.description_active WHERE conceptId = 1050951000168102           AND typeId = 900000000000003001           AND term = 'Australian Schedule 8 controlled medications reference set (foundation metadata concept)';

/* SKIPPED_TEST
	: Expected value tests need to be reviewed (Result = 1)

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'05 PT (in ADRS) is Schedule 8 medications reference set',
	null,
	null
SELECT get_cr_ADRS_PT(1050951000168102) = 'Schedule 8 medications reference set';
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'06 All active members are MPUU,MPP, TPUU or CTPP concepts',
	null,
	null
from <PROSPECTIVE>.concept_active           Where isActiveMemberOf_cr_refset(id, 1050951000168102)           and NOT (isActiveMemberOf_cr_refset(id, 929360071000036103)               OR isActiveMemberOf_cr_refset(id, 929360081000036101)              OR isActiveMemberOf_cr_refset(id, 929360031000036100)                           OR isActiveMemberOf_cr_refset(id, 929360051000036108)                           );

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
select isChildOf_cr(1050951000168102, 32570701000036108);
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
select isChildOf_cr(1050951000168102, 446609009);
*/

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'09 Only has parents 32570701000036108 | Reference sets for Medications AND 446609009 | Simple type reference set',
	null,
	null
from <PROSPECTIVE>.relationship_active           where sourceId = 1050951000168102                                         and typeId = 116680003           and destinationId not in (32570701000036108,446609009);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'10 No duplicate active references (referencedComponentId is unique within reference set)',
	null,
	null
FROM <PROSPECTIVE>.simplerefset_active            WHERE refsetId = 1050951000168102           GROUP BY referencedComponentId           HAVING count(*) > 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'11 All CTPP Members are S8 - (or at least not some other schedule) (4 exceptions)',
	null,
	null
from <PROSPECTIVE>.irefset_active                                         where refsetId = 11000168105               and isActiveMemberOf_cr_refset(referencedComponentId, 1050951000168102)                                          and value1 in (select distinct TGA.ARTGID from TGA_Data.tga_feed AS TGA                                                                                 where TGA.PoisonSchedule NOT RLIKE '(S8|Not scheduled. Not considered by committee)'                                                                                 );

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'12 All S8 products are Members',
	null,
	null
from <PROSPECTIVE>.irefset_active                                         where value1 in (select distinct TGA.ARTGID from TGA_Data.tga_feed AS TGA where PoisonSchedule RLIKE 'S8')                                         and not isActiveMemberOf_cr_refset(referencedComponentId, 1050951000168102);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'13 All TPPus of member CTPPs are also member',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where typeId = 30409011000036107                                         and isActiveMemberOf_cr_refset(sourceId, 1050951000168102)                                         and NOT isActiveMemberOf_cr_refset(destinationId, 1050951000168102);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'14 All CTPPs that have TPUUs that are members are also members',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where typeId = 30409011000036107                                         and NOT isActiveMemberOf_cr_refset(sourceId, 1050951000168102)                                         and isActiveMemberOf_cr_refset(sourceId, 929360051000036108)                                         and isActiveMemberOf_cr_refset(destinationId, 1050951000168102);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'15 S8 Products not in AMT',
	null,
	null
from TGA_Data.tga_feed AS TGA                                         where TGA.PoisonSchedule RLIKE 'S8'                                         and TGA.ARTGID not in (select value1 from <PROSPECTIVE>.irefset_active AS ARTGID                                                                     where ARTGID.refsetid = 11000168105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'16 All single ingredient codeine TPUUs are S8',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where sourceId in (                                                                 select sourceId from <PROSPECTIVE>.relationship_active                                                                 where typeId = 700000081000036101 and destinationId = 1978011000036103                                                                 and isKindOf_cr(sourceId, 30425011000036101)                                                                 )                                         and typeId = 700000081000036101                                         and NOT isActiveMemberOf_cr_refset(sourceId, 1050951000168102)                                         group by sourceId                                         having count(1) = 1;

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'17 All CTPPs with ALWAYS S8 ingredients are in S8 Refset',
	null,
	null
from <PROSPECTIVE>.simplerefset_active where refsetId = 929360051000036108                                         and get_cr_FSN(referencedComponentId) RLIKE '( |\\(|^)(ACETYLDIHYDROCODEINE|ACETYLMETHADOL|ACETYLMORPHINES|ALFENTANIL|ALPHACETYLMETHADOL|ALPHAPRODINE|ALPRAZOLAM|AMFETAMINE|ANILERIDINE|BENZYLMORPHINE|BEZITRAMIDE|BUPRENORPHINE|BUTOBARBITAL|BUTORPHANOL|CARFENTANYL|COCAINE|CODEINE-N-OXIDE|CYCLOBARBITAL|DEXAMFETAMINE|DEXTROMORAMIDE|DIHYDROMORPHINE|DIPIPANONE|DRONABINOL|DROTEBANOL|ETHYLAMFETAMINE|FENTANYL|FLUNITRAZEPAM|HYDROCODONE|HYDROMORPHINOL|HYDROMORPHONE|KETAMINE|LEVAMFETAMINE|LEVOMETHAMFETAMINE|LEVOMORAMIDE|LEVORPHANOL|LISDEXAMFETAMINE|METHADONE|METAMFETAMINE|METHYLDIHYDROMORPHINE|METHYLPHENIDATE|MORPHINE|NABILONE|NORCODEINE|NORMETHADONE|OPIUM|OXYCODONE|OXYMORPHONE|PENTAZOCINE|PETHIDINE|PHENDIMETRAZINE|PHENMETRAZINE|PHENOPERIDINE|PIRITRAMIDE|PROPIRAM|RACEMORAMIDE|REMIFENTANIL|SECBUTOBARBITAL|SECOBARBITAL|OXYBATE|SUFENTANIL|TAPENTADOL|THEBACON|THEBAINE|TILIDINE|nabiximol|.*TETRAHYDROCANN|NABILONE).*'                                         and referencedComponentId not IN (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1050951000168102)                                         and referencedComponentId not IN (1634371000168108,1635881000168100,1635851000168107,1636781000168100,1634891000168103);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'18 All TPUUs with ALWAYS S8 ingredients are in S8 Refset',
	null,
	null
from <PROSPECTIVE>.simplerefset_active where refsetId = 929360031000036100                                         and get_cr_FSN(referencedComponentId) RLIKE '( |\\(|^)(ACETYLDIHYDROCODEINE|ACETYLMETHADOL|ACETYLMORPHINES|ALFENTANIL|ALPHACETYLMETHADOL|ALPHAPRODINE|ALPRAZOLAM|AMFETAMINE|ANILERIDINE|BENZYLMORPHINE|BEZITRAMIDE|BUPRENORPHINE|BUTOBARBITAL|BUTORPHANOL|CARFENTANYL|COCAINE|CODEINE-N-OXIDE|CYCLOBARBITAL|DEXAMFETAMINE|DEXTROMORAMIDE|DIHYDROMORPHINE|DIPIPANONE|DRONABINOL|DROTEBANOL|ETHYLAMFETAMINE|FENTANYL|FLUNITRAZEPAM|HYDROCODONE|HYDROMORPHINOL|HYDROMORPHONE|KETAMINE|LEVAMFETAMINE|LEVOMETHAMFETAMINE|LEVOMORAMIDE|LEVORPHANOL|LISDEXAMFETAMINE|METHADONE|METAMFETAMINE|METHYLDIHYDROMORPHINE|METHYLPHENIDATE|MORPHINE|NABILONE|NORCODEINE|NORMETHADONE|OPIUM|OXYCODONE|OXYMORPHONE|PENTAZOCINE|PETHIDINE|PHENDIMETRAZINE|PHENMETRAZINE|PHENOPERIDINE|PIRITRAMIDE|PROPIRAM|RACEMORAMIDE|REMIFENTANIL|SECBUTOBARBITAL|SECOBARBITAL|OXYBATE|SUFENTANIL|TAPENTADOL|THEBACON|THEBAINE|TILIDINE|NABIXIMOL|.*TETRAHYDROCANN|NABILONE).*'                                         and referencedComponentId not in (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetId = 1050951000168102)                                         and referencedComponentId not IN (1634341000168101,1636751000168107,1635821000168104,1634861000168105);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'19 All imediate MPUU parents of TPUU members are included in S8 refset',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where typeId = 116680003                                         and isActiveMemberOf_cr_refset(sourceId, 929360031000036100)                                         and isActiveMemberOf_cr_refset(sourceId, 1050951000168102)                                                                                 and isActiveMemberOf_cr_refset(destinationId, 929360071000036103)                                         and NOT isActiveMemberOf_cr_refset(destinationId, 1050951000168102);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'20 All MPPs that have MPUUs that are S8 members are also members',
	null,
	null
from <PROSPECTIVE>.relationship_active                                         where typeId = 30348011000036104                                         and NOT isActiveMemberOf_cr_refset(sourceId, 1050951000168102)                                         and isActiveMemberOf_cr_refset(sourceId, 929360081000036101)                                         and isActiveMemberOf_cr_refset(destinationId, 1050951000168102);

insert into qa_result (runid, assertionuuid, concept_id, details, component_id, table_name)
	select 
	'<RUNID>',
	'<ASSERTIONUUID>',
	null,
	'20 All MPPs members are ancestors of S8 CTPPs',
	null,
	null
from <PROSPECTIVE>.relationship_active CTPP_TPP                                         join <PROSPECTIVE>.relationship_active TPP_MPP                                         on CTPP_TPP.destinationid = TPP_MPP.sourceid                                         where CTPP_TPP.sourceid in (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetid = 929360051000036108)                                         and TPP_MPP.destinationid in (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetid = 929360081000036101)                                         and TPP_MPP.destinationid in (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetid = 1050951000168102)                                         and CTPP_TPP.sourceid NOT in (select referencedComponentId from <PROSPECTIVE>.simplerefset_active where refsetid = 1050951000168102);

