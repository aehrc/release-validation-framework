/********************************************************************************
	component-centric-snapshot-validation-active-concept-historical-association

	Assertion:
	Verify that there are no active concepts having active historical associations and reasons for inactivation

********************************************************************************/
insert into qa_result (runid, assertionuuid, concept_id, details)
	select
		<RUNID>,
		'<ASSERTIONUUID>',
		a.id,
		concat('Warning: Concept:',a.id,' is active but has active historical association')
	from curr_concept_s a
	where a.active = 1
	and exists (select * from curr_associationrefset_s where active = 1
	 and referencedcomponentid = a.id
	 and refsetid in (
	-- |POSSIBLY EQUIVALENT TO association reference set|
  '900000000000523009',
  -- |MOVED TO association reference set| 
  '900000000000524003',
  -- |MOVED FROM association reference set| 
  '900000000000525002',
  -- |REPLACED BY association reference set| 
  '900000000000526001',
  -- |SAME AS association reference set|
  '900000000000527005',
  -- |WAS A association reference set| 
  '900000000000528000',
  -- |SIMILAR TO association reference set| 
  '900000000000529008',
  -- |ALTERNATIVE association reference set| 
  '900000000000530003',
  -- |REFERS TO concept association reference set|
  '900000000000531004'
));
commit;
